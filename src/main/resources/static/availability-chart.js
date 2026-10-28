/* Shared SVG renderer: counts determine percentages; absent observations remain gaps. */
(function (root) {
  'use strict';
  const NS = 'http://www.w3.org/2000/svg';
  const colors = { technical: '#00D1FF', operational: '#14B8A6' };
  function aggregate(report, minutes) {
    const step = minutes * 60000, buckets = new Map();
    for (let at = Math.floor(report.from / step) * step; at < report.to; at += step)
      buckets.set(at, { from: Math.max(at, report.from), to: Math.min(at + step, report.to), healthy: 0, degraded: 0, lost: 0 });
    for (const point of report.series) {
      const bucket = buckets.get(Math.floor(point.from / step) * step);
      if (!bucket) continue;
      for (const key of ['healthy', 'degraded', 'lost']) bucket[key] += point.metrics[key] || 0;
    }
    return [...buckets.values()].map(b => {
      const total = b.healthy + b.degraded + b.lost;
      return {...b, technical: total ? 100 * (b.healthy + b.degraded) / total : null,
        operational: total ? 100 * b.healthy / total : null};
    });
  }
  function paths(points, key, x, y, baseline) {
    let line = '', area = '', segment = [];
    function flush() {
      if (!segment.length) return;
      // A single observed bucket still spans a visible interval (no interpolation across gaps).
      if (segment.length === 1) { const [px, py, start, end] = segment[0]; segment = [[start, py], [end, py]]; }
      const path = segment.map(([px, py], i) => `${i ? 'L' : 'M'}${px},${py}`).join(' ');
      line += path + ' ';
      area += `${path} L${segment.at(-1)[0]},${baseline} L${segment[0][0]},${baseline} Z `;
      segment = [];
    }
    for (const p of points) {
      if (p[key] == null) flush();
      else segment.push([x((p.from + p.to) / 2), y(p[key]), x(p.from), x(p.to)]);
    }
    flush(); return {line, area};
  }
  class Chart {
    constructor(container, select) {
      this.container = container; this.select = select; this.report = null; this.index = null;
      select.addEventListener('change', () => { this.index = null; this.selection = null; this.draw(); });
      this.observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => {
        const width = Math.round(container.getBoundingClientRect().width);
        if (width !== this.width) this.draw();
      });
      this.observer?.observe(container);
    }
    update(report) {
      if (this.report === report || (report.version && this.report?.version === report.version)) return;
      this.report = report; this.draw();
    }
    clear() { this.report = null; this.index = null; this.selection = null; this.container.replaceChildren(); }
    draw() {
      if (!this.report || this.report.evaluatedAt == null) { this.container.replaceChildren(); return; }
      const report = this.report, container = this.container;
      const points = aggregate(report, Number(this.select.value) || 1);
      this.width = Math.round(container.getBoundingClientRect().width);
      const width = Math.max(220, this.width), height = this.expanded ? Math.max(300, window.innerHeight - 200) : width < 500 ? 300 : 360;
      const left = 46, right = width - 12, top = 20, bottom = height - 46;
      const x = at => left + (at - report.from) / Math.max(1, report.to - report.from) * (right - left);
      const y = value => bottom - value / 100 * (bottom - top);
      const svg = document.createElementNS(NS, 'svg');
      svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
      svg.setAttribute('tabindex', '0'); svg.setAttribute('role', 'group');
      svg.setAttribute('aria-label', 'Disponibilidade ao longo do tempo. Use as setas para consultar os intervalos e Escape para fechar.');
      const node = (tag, attrs, text, parent = svg) => {
        const el = document.createElementNS(NS, tag);
        Object.entries(attrs).forEach(([key, value]) => el.setAttribute(key, value));
        if (text) el.textContent = text;
        parent.append(el); return el;
      };
      const defs = node('defs', {});
      const gradient = node('linearGradient', {id: 'availability-operational-fill', x1: '0', y1: '0', x2: '0', y2: '1'}, null, defs);
      node('stop', {offset: '0%', 'stop-color': colors.operational, 'stop-opacity': '.32'}, null, gradient);
      node('stop', {offset: '100%', 'stop-color': colors.operational, 'stop-opacity': '0'}, null, gradient);
      for (const value of [0, 25, 50, 75, 100]) {
        node('line', {x1: left, x2: right, y1: y(value), y2: y(value), class: 'availability-gridline'});
        node('text', {x: left - 10, y: y(value) + 4, 'text-anchor': 'end', class: 'availability-axis'}, value + '%');
      }
      const count = Math.max(2, Math.min(8, Math.floor((right - left) / 125) + 1, Math.floor((report.to - report.from) / 60000) + 1));
      const tickFormat = new Intl.DateTimeFormat('pt-BR', {day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit'});
      for (let i = 0; i < count; i++) {
        const at = report.from + (report.to - report.from) * i / (count - 1);
        node('line', {x1: x(at), x2: x(at), y1: top, y2: bottom, class: 'availability-gridline', 'stroke-dasharray': '3 5'});
        const label = node('text', {x: x(at), y: height - 23, 'text-anchor': i === 0 ? 'start' : i === count - 1 ? 'end' : 'middle', class: 'availability-axis'}, width >= 400 ? tickFormat.format(at) : '');
        if (width < 400) {
          node('tspan', {x:x(at), dy:0}, new Date(at).toLocaleDateString('pt-BR', {day:'2-digit',month:'2-digit'}), label);
          node('tspan', {x:x(at), dy:14}, new Date(at).toLocaleTimeString('pt-BR', {hour:'2-digit',minute:'2-digit'}), label);
        }
      }
      const operational = paths(points, 'operational', x, y, bottom);
      if (this.mode === 'bars') {
        // One SVG path per series keeps the DOM bounded even with thousands of intervals.
        for (const key of ['operational', 'technical']) {
          let bars = '';
          for (const p of points) {
            if (p[key] == null) continue;
            const band = x(p.to) - x(p.from), w = band * .38;
            const start = x(p.from) + band * (key === 'technical' ? .53 : .07);
            bars += `M${start},${bottom}V${y(p[key])}h${w}V${bottom}Z `;
          }
          node('path', {d: bars, fill: colors[key], 'fill-opacity': '.8'});
        }
      } else {
        node('path', {d: operational.area, fill: 'url(#availability-operational-fill)', stroke: 'none'});
        for (const key of ['operational', 'technical']) {
          node('path', {d: key === 'operational' ? operational.line : paths(points, key, x, y, bottom).line,
            fill: 'none', stroke: colors[key], 'stroke-width': 2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round'});
        }
      }
      const focus = node('g', {visibility: 'hidden', 'pointer-events': 'none'});
      const guide = node('line', {y1: top, y2: bottom, stroke: '#cbd5e1', 'stroke-dasharray': '4 5'}, null, focus);
      const dots = {};
      for (const key of ['technical', 'operational']) dots[key] = node('circle', {r: 4.5, fill: colors[key], stroke: '#e2e8f0', 'stroke-width': 1.5}, null, focus);
      const tooltip = document.createElement('div'); tooltip.className = 'availability-tooltip'; tooltip.hidden = true;
      tooltip.setAttribute('role', 'status'); tooltip.setAttribute('aria-live', 'polite');
      const time = document.createElement('p'); tooltip.append(time);
      const values = {};
      for (const [key, label] of [['technical', 'Técnica'], ['operational', 'Operacional']]) {
        const row = document.createElement('div'), name = document.createElement('span'), value = document.createElement('strong');
        name.style.color = colors[key]; name.textContent = '● ' + label; row.append(name, value); tooltip.append(row); values[key] = value;
      }
      container.replaceChildren(svg, tooltip);
      const hide = () => { focus.setAttribute('visibility', 'hidden'); tooltip.hidden = true; this.index = null; this.selection = null; };
      const show = (index, px, py) => {
        const p = points[index]; if (!p) return;
        this.index = index; this.selection = {at: p.from, px, py};
        const at = x((p.from + p.to) / 2);
        guide.setAttribute('x1', at); guide.setAttribute('x2', at); focus.setAttribute('visibility', 'visible');
        time.textContent = `${new Date(p.from).toLocaleString('pt-BR')} – ${new Date(p.to).toLocaleTimeString('pt-BR')}`;
        for (const key of ['technical', 'operational']) {
          values[key].textContent = p[key] == null ? 'Sem dados' : p[key].toLocaleString('pt-BR', {minimumFractionDigits: 2, maximumFractionDigits: 2}) + '%';
          dots[key].setAttribute('visibility', p[key] == null ? 'hidden' : 'visible');
          dots[key].setAttribute('cx', at); dots[key].setAttribute('cy', y(p[key] || 0));
        }
        tooltip.hidden = false;
        const rect = tooltip.getBoundingClientRect(), bounds = container.getBoundingClientRect();
        const targetX = px == null ? at / width * bounds.width : px;
        const targetY = py == null ? 30 : py;
        tooltip.style.left = Math.max(4, Math.min(bounds.width - rect.width - 4,
          targetX + 16 + rect.width > bounds.width ? targetX - rect.width - 16 : targetX + 16)) + 'px';
        tooltip.style.top = Math.max(4, Math.min(bounds.height - rect.height - 4, targetY + 12)) + 'px';
      };
      const move = event => {
        const rect = svg.getBoundingClientRect(), px = (event.clientX - rect.left) / rect.width * width;
        const py = (event.clientY - rect.top) / rect.height * height;
        if (px < left || px > right || py < top || py > bottom) { hide(); return; }
        const at = report.from + (px - left) / (right - left) * (report.to - report.from);
        let lo = 0, hi = points.length - 1;
        while (lo < hi) { const mid = (lo + hi) >> 1; if (points[mid].to <= at) lo = mid + 1; else hi = mid; }
        show(lo, event.clientX - rect.left, event.clientY - rect.top);
      };
      svg.addEventListener('pointermove', move); svg.addEventListener('pointerdown', move);
      svg.addEventListener('pointerleave', hide); svg.addEventListener('pointercancel', hide);
      svg.addEventListener('blur', hide);
      svg.addEventListener('keydown', event => {
        if (event.key === 'Escape') { hide(); return; }
        if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
        event.preventDefault();
        const index = event.key === 'Home' ? 0 : event.key === 'End' ? points.length - 1 :
          Math.max(0, Math.min(points.length - 1, (this.index ?? -1) + (event.key === 'ArrowLeft' ? -1 : 1)));
        show(index);
      });
      if (this.selection) {
        const selected = this.selection;
        const index = points.findIndex(p => p.from <= selected.at && p.to > selected.at);
        if (index >= 0) show(index, selected.px, selected.py); else hide();
      }
    }
  }
  root.AvailabilityChart = { Chart, aggregate, paths };
  if (typeof module !== 'undefined') module.exports = root.AvailabilityChart;
})(typeof window === 'undefined' ? globalThis : window);
