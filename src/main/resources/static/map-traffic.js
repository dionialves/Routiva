'use strict';

const MapTraffic = (() => {
  const operational = new Map();
  const colors = ['#E8F9FF', '#4DE2A7'];
  function number(value) {
    if (value == null || typeof value === 'string' && !value.trim()) return null;
    const n = Number(value);
    return Number.isFinite(n) && n >= 0 ? n : null;
  }
  function count(length) {
    return Math.max(1, Math.round(length / 110));
  }
  function utilizationColor(bps, capacity) {
    if (bps == null || !(capacity > 0)) return null;
    const usage = bps / capacity * 100;
    return usage < 60 ? '#22C55E' : usage < 80 ? '#EAB308'
      : usage < 90 ? '#F97316' : '#EF4444';
  }
  function rate(value, fixed = false) {
    const n = number(value);
    if (n == null) return 'Sem dados';
    const giga = n >= 1e9;
    return (n / (giga ? 1e9 : 1e6)).toLocaleString('pt-BR', {minimumFractionDigits: fixed ? 2 : 0, maximumFractionDigits: 2}) + (giga ? ' Gbps' : ' Mbps');
  }
  function state(reading, now = Date.now()) {
    const fresh = e => e && !e.error && now - e.measuredAt <= 30000 && now >= e.measuredAt;
    const down = [reading?.a, reading?.b].some(e => fresh(e) && !e.up);
    const a = reading?.a;
    return {down, tx: !down && fresh(a) ? number(a.txBps) : null,
      rx: !down && fresh(a) ? number(a.rxBps) : null, capacity: number(a?.capacityBps)};
  }
  function render(svg, layer, path, route, nodes, reading, enabled, reverse = false) {
    const data = state(reading);
    const id = layer.trafficContext?.link.mappedConnectionId || reading?.id;
    const previous = operational.get(id) || [null, null];
    [reading?.a, reading?.b].forEach((endpoint, i) => {
      if (endpoint && !endpoint.error && Date.now() >= endpoint.measuredAt && Date.now() - endpoint.measuredAt <= 30000)
        previous[i] = endpoint.up;
    });
    if (id) operational.set(id, previous);
    data.down = previous.includes(false);
    if (data.down) { data.tx = null; data.rx = null; }
    path.classList.toggle('traffic-down', data.down);
    const d = path.getAttribute('d');
    const color = data.down ? '#EF4444' : utilizationColor(data.tx, data.capacity);
    path.style?.setProperty('--traffic-color', color || '#8995aa');
    path.classList.toggle('traffic-measured', Boolean(color));
    // Geometry owns the animation. Metric updates only change visibility, color and text.
    const signature = JSON.stringify([d, reverse]);
    if (layer.trafficGeometry !== signature) {
      layer.trafficGeometry = signature;
      layer.trafficLabelSignature = null;
      layer.replaceChildren();
      const length = path.getTotalLength();
      const period = Math.max(1, length / count(length));
      const width = Math.min(32, period * 0.38);
      const pulses = svg('g', {class: 'traffic-pulses'});
      // Concentric dash lengths form a longitudinal light gradient on the actual path,
      // including curves. All layers share a period and phase, with no moving objects.
      for (let i = 0; i < 12; i++) {
        const dash = width * (1 - i / 12);
        pulses.append(svg('path', {d, fill: 'none', stroke: '#F0FCFF',
          'stroke-width': 2.4, 'stroke-linecap': 'butt', opacity: 0.10 + i * 0.012,
          'stroke-dasharray': `${dash} ${period - dash}`, class: 'traffic-pulse',
          style: `--pulse-from:${dash / 2}px;--pulse-to:${dash / 2 - period}px;animation-duration:${period / 55}s;animation-direction:${reverse ? 'reverse' : 'normal'}`}));
      }
      layer.append(pulses);
      layer.trafficPulses = pulses;
      layer.trafficLabel = svg('text', {class: 'traffic-label'});
      layer.append(layer.trafficLabel);
    }
    layer.trafficPulses.setAttribute('visibility', enabled && data.tx > 0 ? 'visible' : 'hidden');
    const labelSignature = JSON.stringify([data, route, nodes]);
    if (layer.trafficLabelSignature === labelSignature) return;
    layer.trafficLabelSignature = labelSignature;
    // Try the longest segments first and avoid equipment and other link labels.
    const candidates = route.slice(1).map((b, i) => ({a: route[i], b,
      length: Math.hypot(b.x - route[i].x, b.y - route[i].y)})).sort((a, b) => b.length - a.length);
    let position;
    const occupied = [...layer.parentNode.querySelectorAll('.traffic-label')]
      .filter(label => !layer.contains(label)).map(label => ({x: Number(label.getAttribute('x')), y: Number(label.getAttribute('y'))}));
    for (const segment of candidates) {
      for (const fraction of [0.5, 0.35, 0.65]) {
        const center = {x: segment.a.x + (segment.b.x - segment.a.x) * fraction,
          y: segment.a.y + (segment.b.y - segment.a.y) * fraction};
        for (const side of [1, -1]) {
          const vertical = Math.abs(segment.b.y - segment.a.y) > Math.abs(segment.b.x - segment.a.x);
          const p = {x: center.x + (vertical ? side > 0 ? 12 : -202 : -95),
            y: center.y + (vertical ? -7 : side > 0 ? 18 : -28)};
          position = p;
          if (!nodes.some(n => p.x < n.x + 75 && p.x + 190 > n.x - 75 && p.y < n.y + 50 && p.y + 25 > n.y - 50)
            && !occupied.some(n => Math.abs(n.x - p.x) < 190 && Math.abs(n.y - p.y) < 28)) break;
          position = null;
        }
        if (position) break;
      }
      if (position) break;
    }
    if (!position) position = {x: route[0].x + 12, y: route[0].y + 60};
    const label = layer.trafficLabel;
    label.replaceChildren();
    label.setAttribute('x', position.x);
    label.setAttribute('y', position.y);
    const values = data.down ? ['Enlace indisponível'] : [`TX A → B: ${rate(data.tx, true)}`, `RX B → A: ${rate(data.rx, true)}`];
    values.forEach((value, i) => {
      const text = svg('tspan', {x: position.x, dy: i ? 15 : 0, fill: data.down ? 'var(--text)' : colors[i]});
      text.textContent = value;
      label.append(text);
    });
  }
  return {number, count, utilizationColor, rate, state, render};
})();
if (typeof module !== 'undefined') module.exports = MapTraffic;
