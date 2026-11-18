'use strict';

const MapTraffic = (() => {
  const operational = new Map();
  const colors = ['#63E4FD', '#4DE2A7'];
  function number(value) {
    if (value == null || typeof value === 'string' && !value.trim()) return null;
    const n = Number(value);
    return Number.isFinite(n) && n >= 0 ? n : null;
  }
  function count(bps, capacity) {
    if (!(bps > 0) || !(capacity > 0)) return 0;
    const usage = bps / capacity * 100;
    return usage <= 5 ? 1 : usage <= 30 ? 2 : usage <= 70 ? 3 : 4;
  }
  function rate(value) {
    const n = number(value);
    if (n == null) return 'Sem dados';
    const giga = n >= 1e9;
    return (n / (giga ? 1e9 : 1e6)).toLocaleString('pt-BR', {maximumFractionDigits: 2}) + (giga ? ' Gbps' : ' Mbps');
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
    const signature = JSON.stringify([data, d, enabled, reverse]);
    if (layer.trafficSignature === signature) return;
    layer.trafficSignature = signature;
    layer.replaceChildren();
    [data.tx, data.rx].forEach((bps, direction) => {
      const total = enabled ? count(bps, data.capacity) : 0;
      for (let i = 0; i < total; i++) {
        const particle = svg('circle', {r: 1.5, cy: direction ? -2.5 : 2.5,
          fill: colors[direction], color: colors[direction], class: 'traffic-particle'});
        const backwards = Boolean(direction) !== reverse;
        const duration = total === 1 ? 9 : 6;
        const phase = (Date.now() / 1000 + i * duration / total) % duration;
        particle.append(svg('animateMotion', {path: d, dur: `${duration}s`,
          begin: `${-phase}s`, repeatCount: 'indefinite',
          rotate: 'auto', keyPoints: backwards ? '1;0' : '0;1', keyTimes: '0;1', calcMode: 'linear'}));
        layer.append(particle);
      }
    });
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
          const p = {x: center.x + (vertical ? side > 0 ? 12 : -120 : -45),
            y: center.y + (vertical ? -7 : side > 0 ? 18 : -28)};
          position = p;
          if (!nodes.some(n => p.x < n.x + 75 && p.x + 110 > n.x - 75 && p.y < n.y + 50 && p.y + 25 > n.y - 50)
            && !occupied.some(n => Math.abs(n.x - p.x) < 110 && Math.abs(n.y - p.y) < 28)) break;
          position = null;
        }
        if (position) break;
      }
      if (position) break;
    }
    if (!position) position = {x: route[0].x + 12, y: route[0].y + 60};
    const label = svg('text', {...position, class: 'traffic-label'});
    const values = data.down ? ['Enlace indisponível'] : [`TX A → B · ${rate(data.tx)}`, `RX B → A · ${rate(data.rx)}`];
    values.forEach((value, i) => {
      const text = svg('tspan', {x: position.x, dy: i ? 15 : 0, fill: data.down ? 'var(--text)' : colors[i]});
      text.textContent = value;
      label.append(text);
    });
    layer.append(label);
  }
  return {number, count, rate, state, render};
})();
if (typeof module !== 'undefined') module.exports = MapTraffic;
