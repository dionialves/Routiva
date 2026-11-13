/* Shared latency and packet-loss history chart. */
(() => {
  'use strict';
  const format = window.AvailabilityView.format;
  function chartEmpty(container, message) {
    container.replaceChildren();
    const empty = document.createElement('p'); empty.className = 'diagnostic-chart-empty';
    empty.append(window.AppIcon('chart-line'), document.createTextNode(message));
    container.append(empty);
  }
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
      if (!report?.series.some(p => p.metrics.latency != null || p.metrics.loss != null)) { this.clear(); return; }
      container.replaceChildren();
      const ns = 'http://www.w3.org/2000/svg';
      const svg = document.createElementNS(ns, 'svg');
      const width = Math.max(300, container.getBoundingClientRect().width), height = 260;
      svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
      svg.setAttribute('role', 'group'); svg.setAttribute('tabindex', '0');
      svg.setAttribute('aria-label', 'Latência média em milissegundos e perda de pacotes em porcentagem. Use as setas para consultar os intervalos.');
      const node = (tag, attrs, text) => {
        const el = document.createElementNS(ns, tag);
        Object.entries(attrs).forEach(([k, v]) => el.setAttribute(k, v));
        if (text) el.textContent = text; svg.append(el); return el;
      };
      const max = key === 'loss' ? 100 : Math.max(1, ...report.series.map(p => p.metrics[key] || 0)) * 1.1;
      const left = 65, right = width - 58, bottom = 216;
      const x = at => left + (at - report.from) / Math.max(1, report.to - report.from) * (right - left);
      const y = value => bottom - value / max * 192;
      for (const ratio of [0, .5, 1]) {
        node('line', { x1:left, x2:right, y1:y(max * ratio), y2:y(max * ratio), class:'availability-gridline' });
        node('text', { x:left - 8, y:y(max * ratio)+4, 'text-anchor':'end', class:'availability-axis' }, (max * ratio).toFixed(1) + unit);
      }
      const lossY = value => bottom - value / 100 * 192;
      for (const ratio of [0, .5, 1]) {
        node('text', { x:right + 8, y:lossY(100 * ratio)+4, 'text-anchor':'start', class:'availability-axis' }, (100 * ratio).toFixed(0) + '%');
      }
      const points = report.series.map(p => ({ ...p, latency:p.metrics.latency, loss:p.metrics.loss }));
      node('path', { d:window.AvailabilityChart.paths(points,'loss',x,lossY,bottom).line, fill:'none', stroke:'#f59e0b', 'stroke-width':2 });
      node('path', { d:window.AvailabilityChart.paths(points,key,x,y,bottom).line, fill:'none', stroke:color, 'stroke-width':2 });
      const date = at => new Date(at).toLocaleString('pt-BR', {timeZone:'America/Sao_Paulo',day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit'});
      for (const [at, anchor] of [[report.from, 'start'], [report.to, 'end']]) {
        node('line', { x1:x(at), x2:x(at), y1:24, y2:bottom, class:'availability-gridline' });
        node('text', {x:x(at), y:248, 'text-anchor':anchor, class:'availability-axis'}, date(at));
      }
      const cursor = node('line', {x1:0,x2:0,y1:24,y2:bottom,stroke:color,visibility:'hidden','stroke-dasharray':'3 4'});
      const dot = node('circle', {cx:0,cy:0,r:3,fill:color,visibility:'hidden'});
      const lossDot = node('circle', {cx:0,cy:0,r:3,fill:'#f59e0b',visibility:'hidden'});
      const tooltip = document.createElement('div'); tooltip.className = 'diagnostic-chart-tooltip'; tooltip.hidden = true;
      tooltip.setAttribute('role','status');
      const show = index => {
        this.index = Math.max(0, Math.min(points.length - 1, index));
        const point = points[this.index], px = x((point.from + point.to) / 2);
        cursor.setAttribute('x1',px); cursor.setAttribute('x2',px); cursor.setAttribute('visibility','visible');
        dot.setAttribute('visibility',point[key] == null ? 'hidden' : 'visible');
        dot.setAttribute('cx',px); dot.setAttribute('cy',y(point[key] || 0));
        lossDot.setAttribute('visibility',point.loss == null ? 'hidden' : 'visible');
        lossDot.setAttribute('cx',px); lossDot.setAttribute('cy',lossY(point.loss || 0));
        tooltip.textContent = `${date(point.from)} — ${date(point.to)} · Latência: ${format(point.latency, ' ms')} · Perda: ${format(point.loss, '%')}`;
        tooltip.hidden = false;
        tooltip.style.left = Math.max(8,Math.min(px,width - Math.min(280,width - 16))) + 'px';
      };
      const hide = () => { tooltip.hidden = true; cursor.setAttribute('visibility','hidden'); dot.setAttribute('visibility','hidden'); lossDot.setAttribute('visibility','hidden'); };
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
  window.PerformanceChart = MetricChart;
})();
