'use strict';

// Coordinates are local to the equipment; links and previews share this geometry.
const MapSwitch = (() => {
  const types = ['rj45', 'sfp', 'qsfp'];
  let tintSequence = 0;
  const capacities = {electrical: {label: 'Elétrica', type: 'rj45'}, sfp1g: {label: 'SFP1G', type: 'sfp'},
    sfp10g: {label: 'SFP10G', type: 'sfp'}, sfp40g: {label: 'SFP40G', type: 'qsfp'}, sfp100g: {label: 'SFP100G', type: 'qsfp'}};
  const names = {rj45: 'RJ45', sfp: 'SFP', qsfp: 'QSFP+ 40G'};
  function portsFor(previous, counts) {
    return types.flatMap(type => Array.from({length: counts[type]}, (_, index) =>
      previous.filter(port => port.type === type)[index] || {id: crypto.randomUUID(), type}));
  }
  function slotsFor(node) {
    if (node.slots?.length) return node.slots.map(slot => ({...slot}));
    const defaults = {rj45: ['ether', 'electrical'], sfp: ['sfp-sfpplus', 'sfp10g'], qsfp: ['qsfp', 'sfp40g']};
    return types.flatMap(type => {
      const members = (node.ports || []).filter(port => port.type === type);
      return members.length ? [{id: 'legacy-' + type, name: defaults[type][0], capacity: defaults[type][1], count: members.length}] : [];
    });
  }
  function portsForSlots(previous, slots) {
    if (slots.some(slot => !slot.id || !slot.name.trim() || slot.name.length > 80 || !capacities[slot.capacity]
        || !Number.isInteger(slot.count) || slot.count < 1 || slot.count > 96)) return null;
    return slots.flatMap(slot => {
      const type = capacities[slot.capacity].type;
      const existing = previous.filter(port => port.slotId === slot.id ||
        (!port.slotId && slot.id === 'legacy-' + port.type));
      return Array.from({length: slot.count}, (_, index) => ({
        id: existing[index]?.id || crypto.randomUUID(), type, slotId: slot.id,
        name: slot.name.trim() + (index + 1), capacity: slot.capacity
      }));
    });
  }
  function geometry(node) {
    const ports = node.ports || [];
    const keys = [...new Set(ports.map(port => port.slotId || port.type))];
    const groups = keys.map(key => {
      const members = ports.filter(port => (port.slotId || port.type) === key);
      const columns = members.length <= 2 ? members.length : Math.ceil(members.length / 2);
      const pitch = 23;
      return {members, columns, pitch, width: Math.max(0, columns - 1) * pitch + 24};
    }).filter(group => group.members.length);
    const gap = 24;
    const contentWidth = groups.reduce((sum, group) => sum + group.width, 0) + Math.max(0, groups.length - 1) * gap;
    const width = Math.max(260, contentWidth + 76);
    // Center within the front panel, reserving room for the status LEDs on the left.
    let left = 12 - contentWidth / 2;
    const centerY = 17;
    const positioned = [];
    for (const group of groups) {
      group.members.forEach((port, index) => {
        const singleRow = group.members.length <= 2;
        const column = singleRow ? index : Math.floor(index / 2);
        const row = index % 2;
        const rowGap = 25;
        positioned.push({...port, label: port.name ? port.name + ' · ' + (capacities[port.capacity]?.label || names[port.type]) : names[port.type] + ' ' + (index + 1),
          x: left + 12 + column * group.pitch,
          y: centerY + (singleRow || row === 0 ? rowGap / 2 : -rowGap / 2)});
      });
      left += group.width + gap;
    }
    return {width, height: 110, ports: positioned};
  }

  function endpoint(node, portId) {
    const port = node.interfacesEnabled !== false && node.icon !== 'circle' && portId
      && geometry(node).ports.find(port => port.id === portId);
    return {x: node.x + (port ? port.x : 0), y: node.y + (port ? port.y : 0)};
  }
  function image(svg, parent, file, x, y, width, height, filter) {
    parent.append(svg('image', {href: '/map-assets/' + file + '.svg' + (file === 'switch' ? '?v=20261117-ping-led' : ''), x, y, width, height,
      preserveAspectRatio: 'none', 'aria-hidden': 'true', 'pointer-events': 'none', ...(filter ? {filter} : {})}));
  }
  function pingIndicator(state) {
    return state === 'online' ? {color: '#58bd83', label: 'Online (ping)'}
      : state === 'offline' ? {color: '#ff0000', label: 'Offline (ping)'}
      : {color: '#687486', label: 'Sem dados recentes de ping'};
  }
  function draw(svg, group, node, fill, selected, options = {}) {
    const shape = geometry(node.interfacesEnabled === false ? {...node, ports: []} : node);
    let bodyFilter;
    if (/^#[0-9a-fA-F]{6}$/.test(node.switchColor || '')) {
      const id = 'switch-tint-' + (++tintSequence);
      const channels = [1, 3, 5].map(offset => parseInt(node.switchColor.slice(offset, offset + 2), 16) / 255);
      const matrix = channels.flatMap(channel => [channel * 0.2126, channel * 0.7152, channel * 0.0722, 0, 0]);
      const defs = svg('defs', {}), filter = svg('filter', {id, 'color-interpolation-filters': 'sRGB'});
      filter.append(svg('feColorMatrix', {type: 'matrix', values: [...matrix, 0, 0, 0, 1, 0].join(' ')}));
      defs.append(filter); group.append(defs); bodyFilter = 'url(#' + id + ')';
    }
    image(svg, group, 'switch', -shape.width / 2, -55, shape.width, 110, bodyFilter);
    group.append(svg('rect', {x: -shape.width / 2, y: -55, width: shape.width, height: 110,
      rx: 10, fill: 'transparent', stroke: selected ? 'var(--focus)' : 'none', 'stroke-width': 3,
      'pointer-events': 'all'}));
    const status = pingIndicator(options.pingState);
    const led = svg('circle', {cx: -shape.width / 2 + shape.width * 0.0557, cy: 17, r: 3.5,
      fill: status.color, class: 'switch-status-led', 'data-ping-host': node.hostId || '',
      role: 'img', 'aria-label': status.label});
    const statusTitle = svg('title', {}); statusTitle.textContent = status.label;
    led.append(statusTitle); group.append(led);
    for (const port of shape.ports) {
      const g = svg('g', {transform: `translate(${port.x} ${port.y})`, class: 'switch-port', 'data-port-id': port.id,
        ...(options.interactive ? {tabindex: 0, role: 'button', 'aria-label': port.label,
          'aria-pressed': String(options.origin === port.id)} : {})});
      const title = svg('title', {});
      title.textContent = port.label;
      g.append(title);
      image(svg, g, port.type, -11, -10, 22, 19);
      g.append(svg('rect', {x: -11, y: -10, width: 22, height: 20, rx: 2,
        class: 'switch-port-hit' + (options.origin === port.id ? ' is-origin' : '')}));
      if (options.interactive) {
        g.onpointerdown = event => {
          if (options.connect && event.button === 0) { event.stopPropagation(); options.onPort(port.id); }
        };
        g.onkeydown = event => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault(); event.stopPropagation(); options.onPort(port.id);
          }
        };
      }
      group.append(g);
    }
    const offsets = {ABOVE: {x: 0, y: -67, 'text-anchor': 'middle'},
      BELOW: {x: 0, y: 74, 'text-anchor': 'middle'},
      LEFT: {x: -shape.width / 2 - 12, y: 0, 'text-anchor': 'end'},
      RIGHT: {x: shape.width / 2 + 12, y: 0, 'text-anchor': 'start'}};
    const label = svg('text', offsets[node.labelPosition] || offsets.BELOW);
    label.textContent = node.name;
    group.append(label);
  }
  return {types, capacities, slotsFor, portsForSlots, portsFor, geometry, endpoint, pingIndicator, draw};
})();
if (typeof module !== 'undefined') module.exports = MapSwitch;
