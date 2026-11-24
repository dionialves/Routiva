'use strict';

const MapTraffic = (() => {
  let nextMask = 0;
  function isWireless(link, nodes) {
    const endpoints = [['from', 'fromPort'], ['to', 'toPort']].map(([nodeKey, portKey]) => {
      const node = nodes.find(node => node.id === link[nodeKey]);
      const port = node?.ports?.find(port => port.id === link[portKey]);
      return {node, port, wireless: port?.connector === 'wireless' || port?.type === 'wireless',
        portId: link[portKey]};
    });
    if (endpoints.every(endpoint => endpoint.wireless)) return true;
    return endpoints.some((endpoint, i) => ['radio', 'sector-radio'].includes(endpoint.node?.icon)
      && (endpoint.wireless || !endpoint.portId) && Boolean(endpoints[1 - i].node?.groupId));
  }

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
  function fresh(e, now = Date.now()) {
    return Boolean(e && !e.error && e.measuredAt > 0 && now >= e.measuredAt
      && now <= (e.validUntil ?? e.measuredAt + 30000));
  }
  function state(reading, now = Date.now()) {
    const down = [reading?.a, reading?.b].some(e => fresh(e, now) && !e.up);
    const a = reading?.a;
    return {down, tx: !down && fresh(a, now) ? (now <= (a.txValidUntil ?? a.measuredAt + 30000) ? number(a.txBps) : null) : null,
      rx: !down && fresh(a, now) ? (now <= (a.rxValidUntil ?? a.measuredAt + 30000) ? number(a.rxBps) : null) : null, capacity: number(a?.capacityBps)};
  }
  function mappedSourcePort(link, nodes, reading, definition = link?.connection) {
    const hostId = reading?.hostA || definition?.hostA;
    const name = reading?.a?.name || definition?.interfaceA;
    if (!hostId || !name) return null;
    for (const side of ['from', 'to']) {
      const node = nodes.find(node => node.id === link?.[side]);
      if (node?.hostId === hostId) return node.ports?.find(port => port.name === name);
      const entry = node?.linkInterfaces?.find(entry => entry.hostId === hostId && entry.name === name);
      if (entry) return entry;
    }
    return null;
  }
  function render(svg, layer, path, route, nodes, reading, enabled, reverse = false, now = Date.now()) {
    const wireless = Boolean(layer.trafficContext?.wireless);
    const data = state(reading, now);
    const link = layer.trafficContext?.link;
    const explicitPulses = link?.appearance?.endsWith('-pulses');
    const solidPulses = link?.appearance === 'solid-pulses';
    const capacities = { '10m': 1e7, '100m': 1e8, '1g': 1e9, '10g': 1e10,
      '25g': 25e9, '40g': 40e9, '50g': 50e9, '100g': 100e9,
      '200g': 200e9, '400g': 400e9, '800g': 800e9 };
    const source = nodes.find(node => node.id === link?.[reverse ? 'to' : 'from']);
    const port = source?.ports?.find(port => port.id === link?.[reverse ? 'toPort' : 'fromPort'])
      || source?.linkInterfaces?.find(entry => entry.hostId === (reading?.hostA || link?.connection?.hostA)
        && entry.name === (reading?.a?.name || link?.connection?.interfaceA));
    data.capacity = capacities[port?.capacity] || data.capacity;
    data.down = [reading?.a, reading?.b].some(endpoint => fresh(endpoint, now) && !endpoint.up);
    if (data.down || mappedSourcePort(link, nodes, reading)?.connector === 'pon') {
      data.tx = null;
      data.rx = null;
    }
    path.classList.toggle('traffic-down', (!explicitPulses || solidPulses) && data.down);
    const d = path.getAttribute('d');
    const utilization = utilizationColor(data.tx, data.capacity);
    const color = solidPulses
      ? data.down ? '#EF4444' : utilization && utilization !== '#22C55E' ? utilization : '#16A34A'
      : explicitPulses ? '#22C55E' : data.down ? '#EF4444' : utilization || '#687486';
    path.style?.setProperty('--traffic-color', color);
    path.classList.toggle('traffic-measured', Boolean(color));
    // Geometry owns the animation. Metric updates only change visibility and color.
    const signature = JSON.stringify([d, reverse, wireless, solidPulses]);
    if (layer.trafficGeometry !== signature) {
      layer.trafficGeometry = signature;
      layer.replaceChildren();
      const pulses = svg('g', {class: 'traffic-pulses'});
      if (solidPulses) {
        pulses.setAttribute('class', 'traffic-pulses traffic-pulses-solid');
        for (const [className, stroke, thickness] of [
          ['traffic-solid-glow', '#00FF88', 7], ['traffic-solid-core', '#D1FFE4', 3.5],
        ]) {
          pulses.append(svg('path', {d, fill: 'none', stroke, 'stroke-width': thickness,
            'stroke-linecap': 'round', 'stroke-dasharray': '24 106',
            class: `traffic-pulse ${className}`, style: reverse ? 'animation-direction:reverse' : ''}));
        }
      } else {
        renderLegacyPulses(svg, layer, pulses, path, route, d, wireless, reverse);
      }
      layer.append(pulses);
      layer.trafficPulses = pulses;
    }
    const animate = enabled && (!solidPulses || !data.down) && (explicitPulses || data.tx > 0 || data.rx > 0);
    layer.trafficPulses.setAttribute('visibility', animate ? 'visible' : 'hidden');
    if (wireless) {
      layer.style?.setProperty('--wireless-pulse-color', color);
    }
  }

  function renderLegacyPulses(svg, layer, pulses, path, route, d, wireless, reverse) {
    const length = path.getTotalLength();
    const period = Math.max(1, length / count(length));
    const width = Math.min(32, period * 0.38);
    // Concentric dash lengths form a longitudinal light gradient on the actual path,
    // including curves. All layers share a period and phase, with no moving objects.
    if (wireless) {
      const maskId = `wireless-pulse-mask-${++nextMask}`;
      const gradientId = `${maskId}-fade`;
      const first = route[0], last = route[route.length - 1];
      const defs = svg('defs', {});
      const gradient = svg('linearGradient', {id: gradientId, gradientUnits: 'userSpaceOnUse',
        x1: first.x, y1: first.y, x2: last.x, y2: last.y});
      for (const [offset, opacity] of [[0, 0], [Math.min(0.2, 12 / Math.max(length, 1)), 1],
        [1 - Math.min(0.2, 12 / Math.max(length, 1)), 1], [1, 0]])
        gradient.append(svg('stop', {offset, 'stop-color': 'white', 'stop-opacity': opacity}));
      const mask = svg('mask', {id: maskId, maskUnits: 'userSpaceOnUse',
        x: Math.min(...route.map(p => p.x)) - length, y: Math.min(...route.map(p => p.y)) - length,
        width: length * 3, height: length * 3});
      mask.append(svg('path', {d, fill: 'none', stroke: `url(#${gradientId})`, 'stroke-width': 24}));
      defs.append(gradient, mask);
      layer.append(defs);
      pulses.setAttribute('mask', `url(#${maskId})`);
      {
        const group = svg('g', {});
        const dash = Math.min(20, period * 0.38);
        for (const [stroke, thickness, opacity, blur] of [
          ['var(--wireless-pulse-color)', 10, 0.22, true],
          ['var(--wireless-pulse-color)', 5, 0.9, false], ['#F0FFF9', 2.5, 1, false]]) {
          group.append(svg('path', {d, fill: 'none', stroke, 'stroke-width': thickness,
            'stroke-linecap': 'round', opacity, 'stroke-dasharray': `${dash} ${period - dash}`,
            class: 'traffic-pulse', style: `${blur ? 'filter:blur(3px);' : ''}--pulse-from:${dash / 2}px;--pulse-to:${dash / 2 - period}px;animation-duration:${period / 55}s;animation-direction:${reverse ? 'reverse' : 'normal'}`}));
        }
        pulses.append(group);
      }
    } else for (let i = 0; i < 12; i++) {
      const dash = width * (1 - i / 12);
      pulses.append(svg('path', {d, fill: 'none', stroke: '#F0FCFF',
        'stroke-width': 2.4, 'stroke-linecap': 'butt', opacity: 0.10 + i * 0.012,
        'stroke-dasharray': `${dash} ${period - dash}`, class: 'traffic-pulse',
        style: `--pulse-from:${dash / 2}px;--pulse-to:${dash / 2 - period}px;animation-duration:${period / 55}s;animation-direction:${reverse ? 'reverse' : 'normal'}`}));
    }
  }

  return {fresh, isWireless, mappedSourcePort, number, count, utilizationColor, rate, state, render};
})();
if (typeof module !== 'undefined') module.exports = MapTraffic;
