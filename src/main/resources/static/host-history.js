/* Host history uses the same chart primitives and layout as group details. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const format = window.AvailabilityView.format;
  function chartEmpty(container, message) {
    const empty = document.createElement('p'); empty.className = 'diagnostic-chart-empty';
    empty.textContent = message; container.replaceChildren(empty);
  }
  const chart = new window.AvailabilityChart.Chart($('availability-chart'), $('chart-granularity'));
  class MetricChart {
    constructor(container, key, unit, color) {
      Object.assign(this, { container, key, unit, color });
      this.index = 0;
      if (typeof ResizeObserver !== 'undefined') {
        this.observer = new ResizeObserver(() => { if (this.report) this.draw(); });
        this.observer.observe(container);
      }

    }
    update(value) { this.report = value; this.draw(); }
    clear(message = 'Sem medições no período.') {
      this.report = null; chartEmpty(this.container, message);
    }
    draw() {
      const report = this.report, { container, key, unit, color } = this;
      if (!report?.series.some(p => p.metrics[key] != null)) { this.clear(); return; }
      container.replaceChildren();
      const ns = 'http://www.w3.org/2000/svg';
      const svg = document.createElementNS(ns, 'svg');
      const width = Math.max(300, container.getBoundingClientRect().width), height = 230;
      svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
      svg.setAttribute('role', 'group'); svg.setAttribute('tabindex', '0');
      svg.setAttribute('aria-label', `${key === 'loss' ? 'Perda de pacotes' : 'Latência média'}. Use as setas para consultar os intervalos.`);
      const node = (tag, attrs, text) => {
        const el = document.createElementNS(ns, tag);
        Object.entries(attrs).forEach(([k, v]) => el.setAttribute(k, v));
        if (text) el.textContent = text; svg.append(el); return el;
      };
      const max = key === 'loss' ? 100 : Math.max(1, ...report.series.map(p => p.metrics[key] || 0)) * 1.1;
      const left = 65, right = width - 16, bottom = 186;
      const x = at => left + (at - report.from) / Math.max(1, report.to - report.from) * (right - left);
      const y = value => bottom - value / max * 162;
      for (const ratio of [0, .5, 1]) {
        node('line', { x1:left, x2:right, y1:y(max * ratio), y2:y(max * ratio), class:'availability-gridline' });
        node('text', { x:left - 8, y:y(max * ratio)+4, 'text-anchor':'end', class:'availability-axis' }, (max * ratio).toFixed(1) + unit);
      }
      const points = report.series.map(p => ({ ...p, [key]:p.metrics[key] }));
      node('path', { d:window.AvailabilityChart.paths(points,key,x,y,bottom).line, fill:'none', stroke:color, 'stroke-width':2 });
      const date = at => new Date(at).toLocaleString('pt-BR', {timeZone:'America/Sao_Paulo',day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit'});
      for (const [at, anchor] of [[report.from, 'start'], [report.to, 'end']]) {
        node('line', { x1:x(at), x2:x(at), y1:24, y2:bottom, class:'availability-gridline' });
        node('text', {x:x(at), y:218, 'text-anchor':anchor, class:'availability-axis'}, date(at));
      }
      const cursor = node('line', {x1:0,x2:0,y1:24,y2:bottom,stroke:color,visibility:'hidden','stroke-dasharray':'3 4'});
      const dot = node('circle', {cx:0,cy:0,r:3,fill:color,visibility:'hidden'});
      const tooltip = document.createElement('div'); tooltip.className = 'diagnostic-chart-tooltip'; tooltip.hidden = true;
      tooltip.setAttribute('role','status');
      const show = index => {
        this.index = Math.max(0, Math.min(points.length - 1, index));
        const point = points[this.index], px = x((point.from + point.to) / 2);
        cursor.setAttribute('x1',px); cursor.setAttribute('x2',px); cursor.setAttribute('visibility','visible');
        dot.setAttribute('visibility',point[key] == null ? 'hidden' : 'visible');
        dot.setAttribute('cx',px); dot.setAttribute('cy',y(point[key] || 0));
        tooltip.textContent = `${date(point.from)} — ${date(point.to)} · ${format(point[key], unit)}`;
        tooltip.hidden = false;
        tooltip.style.left = Math.max(8,Math.min(px,width - Math.min(280,width - 16))) + 'px';
      };
      const hide = () => { tooltip.hidden = true; cursor.setAttribute('visibility','hidden'); dot.setAttribute('visibility','hidden'); };
      svg.addEventListener('pointermove', event => {
        const bounds = svg.getBoundingClientRect();
        const at = report.from + ((event.clientX - bounds.left) * width / bounds.width - left) / (right - left) * (report.to - report.from);
        const index = points.findIndex(p => p.to > at);
        show(index < 0 ? points.length - 1 : index);
      });
      svg.addEventListener('pointerleave',hide);
      svg.addEventListener('focus', () => show(this.index));
      svg.addEventListener('blur',hide);
      svg.addEventListener('keydown', event => {
        if (event.key === 'Escape') hide();
        if (['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) {
          event.preventDefault(); show(event.key === 'Home' ? 0 : event.key === 'End' ? points.length - 1 : this.index + (event.key === 'ArrowRight' ? 1 : -1));
        }
      });
      container.append(svg,tooltip);
    }
  }
  const latencyChart = new MetricChart($('latency-chart'), 'latency', ' ms', '#06b6d4');
  const lossChart = new MetricChart($('loss-chart'), 'loss', '%', '#f59e0b');
  let lastVersion;
  window.HostHistoryCharts = {
    clear(message = 'Carregando período…') {
      lastVersion = undefined;
      chart.clear(); chartEmpty($('availability-chart'), message);
      latencyChart.clear(message); lossChart.clear(message);
    },
    update(report) {
      if (report.version && report.version === lastVersion) return;
      lastVersion = report.version;
      chart.update(report); latencyChart.update(report); lossChart.update(report);
      if (report.evaluatedAt == null || !report.series.some(point => point.metrics.technical != null || point.metrics.operational != null)) {
        chart.clear(); chartEmpty($('availability-chart'), 'Sem medições no período.');
      }
    },
  };
  $('chart-lines').onclick = () => { chart.mode = 'lines'; chart.draw(); $('chart-lines').setAttribute('aria-pressed', 'true'); $('chart-bars').setAttribute('aria-pressed', 'false'); };
  $('chart-bars').onclick = () => { chart.mode = 'bars'; chart.draw(); $('chart-lines').setAttribute('aria-pressed', 'false'); $('chart-bars').setAttribute('aria-pressed', 'true'); };
  $('chart-expand').onclick = () => {
    chart.expanded = !chart.expanded;
    $('chart-expand').setAttribute('aria-expanded', String(chart.expanded));
    $('chart-expand').setAttribute('aria-label', chart.expanded ? 'Recolher gráfico' : 'Expandir gráfico');
    $('availability-chart').closest('.chart-card').classList.toggle('chart-expanded', chart.expanded);
    document.body.classList.toggle('chart-is-expanded', chart.expanded); chart.draw();
  };
  document.addEventListener('keydown', event => { if (event.key === 'Escape' && chart.expanded) $('chart-expand').onclick(); });
  window.addEventListener('pagehide', () => { chart.observer?.disconnect(); latencyChart.observer?.disconnect(); lossChart.observer?.disconnect(); });
})();
