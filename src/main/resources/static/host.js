(() => {
  'use strict';
  const report = window.hostReport;
  const period = document.getElementById('hours');
  period.addEventListener('change', () => period.form.requestSubmit());
  const number = (value) =>
    value == null
      ? 'Indefinida'
      : new Intl.NumberFormat('pt-BR', {
          minimumFractionDigits: 2,
          maximumFractionDigits: 4,
        }).format(value);
  for (const key of ['operational', 'technical', 'loss', 'degradation'])
    document.getElementById(key).textContent =
      number(report.total[key]) + (report.total[key] == null ? '' : '%');
  document.getElementById('counts').textContent =
    `${report.total.healthy} saudáveis · ${report.total.degraded} degradados · ${report.total.lost} perdidos`;
  document.getElementById('no-data').hidden =
    report.total.healthy + report.total.degraded + report.total.lost > 0;
  const time = (value) =>
    new Date(value).toLocaleString('pt-BR', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  document.getElementById('date-range').textContent = `${time(report.from)} — ${time(report.to)}`;
  const ns = 'http://www.w3.org/2000/svg';
  function element(name, attrs, text) {
    const el = document.createElementNS(ns, name);
    for (const [key, value] of Object.entries(attrs)) el.setAttribute(key, value);
    if (text != null) el.textContent = text;
    return el;
  }
  function chart(id, lines, unit, fixedMax) {
    const formatValue =
      id === 'latency-chart'
        ? (value) => new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 2 }).format(value)
        : number;
    const svg = element('svg', {
      viewBox: '0 0 800 270',
      role: 'img',
      'aria-label': document.getElementById(id).parentElement.querySelector('h2').textContent,
    });
    const max = fixedMax || Math.max(1, ...report.series.map((p) => p.metrics.latency || 0)) * 1.1;
    const x = (i) => 65 + ((i + 0.5) / report.series.length) * 710;
    const y = (v) => 215 - (v / max) * 185;
    for (let i = 0; i <= 4; i++) {
      const value = (max * i) / 4;
      svg.append(
        element('line', { x1: 65, x2: 775, y1: y(value), y2: y(value), stroke: '#2c394e' }),
        element(
          'text',
          { x: 55, y: y(value) + 4, 'text-anchor': 'end', fill: '#97a5bd', 'font-size': 12 },
          `${formatValue(value)}${unit}`,
        ),
      );
    }
    for (const [key, color, label] of lines) {
      let path = '';
      let connected = false;
      report.series.forEach((point, i) => {
        const value = point.metrics[key];
        if (value == null) {
          connected = false;
          return;
        }
        path += `${connected ? 'L' : 'M'}${x(i)},${y(value)} `;
        connected = true;
        const dot = element('circle', { cx: x(i), cy: y(value), r: 2, fill: color });
        dot.append(
          element(
            'title',
            {},
            `${time(point.at)} · ${label}: ${formatValue(value)}${unit} · H ${point.metrics.healthy}, D ${point.metrics.degraded}, P ${point.metrics.lost}`,
          ),
        );
        svg.append(dot);
      });
      svg.append(element('path', { d: path, fill: 'none', stroke: color, 'stroke-width': 2 }));
    }
    const axis = element('g', { 'aria-label': 'Horários do período' });
    svg.append(axis);
    const container = document.getElementById(id);
    container.append(svg);
    function renderTimes() {
      axis.replaceChildren();
      const duration = report.to - report.from;
      const scale = svg.getBoundingClientRect().width / 800;
      if (!scale || duration <= 0) return;
      // Reserve enough screen space for HH:mm, even in the narrower charts.
      const spacing = Math.max(46, 52 / scale);
      const intervals = [5, 10, 15, 30, 60, 120, 240, 360, 480, 720, 1440];
      const minimum = duration >= 48 * 3600000 ? 240 : duration >= 24 * 3600000 ? 120 : 5;
      const minutes =
        intervals.find(
          (value) => value >= minimum && ((value * 60000) / duration) * 710 >= spacing,
        ) || 1440;
      const step = minutes * 60000;
      function label(at, position, anchor) {
        const text = element(
          'text',
          { x: position, y: 250, 'text-anchor': anchor, fill: '#97a5bd', 'font-size': 12 },
          new Date(at).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }),
        );
        text.append(element('title', {}, time(at)));
        axis.append(text);
      }
      label(report.from, 65, 'start');
      // Keep the endpoints and omit only labels that would crowd their neighbors.
      for (let at = report.from + step; at < report.to; at += step) {
        const position = 65 + ((at - report.from) / duration) * 710;
        if (position - 65 >= spacing && 775 - position >= spacing) label(at, position, 'middle');
      }
      label(report.to, 775, 'end');
    }
    renderTimes();
    new ResizeObserver(renderTimes).observe(container);
  }
  chart(
    'availability-chart',
    [
      ['operational', '#4ee4b1', 'Operacional'],
      ['technical', '#7dafff', 'Técnica'],
    ],
    '%',
    100,
  );
  chart(
    'loss-chart',
    [
      ['loss', '#ff7b85', 'Perda'],
      ['degradation', '#ffd166', 'Degradação'],
    ],
    '%',
    100,
  );
  chart('latency-chart', [['latency', '#7dafff', 'Latência média']], '');
})();
