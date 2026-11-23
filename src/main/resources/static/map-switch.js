'use strict';

// Coordinates are local to the equipment; links and previews share this geometry.
const MapSwitch = (() => {
  // randomUUID requires HTTPS/localhost; getRandomValues also works on HTTP LAN deployments.
  function newId() {
    if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  const interfaceNames = new Intl.Collator('en', {numeric: true, sensitivity: 'base'});
  const types = ['rj45', 'sfp', 'qsfp', 'wireless'];
  let tintSequence = 0;
  // Socket centers in the shared 22×19 port drawing, independent of cabinet color.
  const portArtwork = {
    wireless: {empty: 'wireless', occupied: 'wireless', socketY: 0, upright: true},
    electrical: {empty: 'rj45', occupied: 'rj45-plugged', socketY: 3.6, upright: true},
    sfp1g: {empty: 'sfp', occupied: 'sfp-plugged', socketY: 4.2, upright: true},
    sfp10g: {empty: 'sfp', occupied: 'sfp-plugged', socketY: 4.2, upright: true},
    sfp40g: {empty: 'qsfp40', occupied: 'qsfp40-plugged', socketY: 4.2},
    sfp100g: {empty: 'qsfp', occupied: 'qsfp100-plugged', socketY: 2.6}
  };
  const capacities = {electrical: {label: 'Elétrica', type: 'rj45'}, sfp1g: {label: 'SFP1G', type: 'sfp'},
    sfp10g: {label: 'SFP10G', type: 'sfp'}, sfp40g: {label: 'SFP40G', type: 'qsfp'}, sfp100g: {label: 'SFP100G', type: 'qsfp'}};
  const connectors = {
    wireless: {label: 'Wireless', type: 'wireless'},
    electrical: {label: 'Elétrico', type: 'rj45'}, sfp: {label: 'SFP', type: 'sfp'},
    sfpplus: {label: 'SFP+', type: 'sfp'}, sfp28: {label: 'SFP28', type: 'sfp'},
    qsfpplus: {label: 'QSFP+', type: 'qsfp'}, sfp56: {label: 'SFP56', type: 'sfp'},
    qsfp28: {label: 'QSFP28', type: 'qsfp'}, qsfp56: {label: 'QSFP56', type: 'qsfp'},
    qsfpdd: {label: 'QSFP-DD', type: 'qsfp'}, qsfpdd800: {label: 'QSFP-DD800', type: 'qsfp'}
  };
  const portCapacities = {'10m': '10 Mbps', '100m': '100 Mbps', '1g': '1 Gbps',
    '10g': '10 Gbps', '25g': '25 Gbps', '40g': '40 Gbps', '50g': '50 Gbps',
    '100g': '100 Gbps', '200g': '200 Gbps', '400g': '400 Gbps', '800g': '800 Gbps'};
  const legacyConnectors = {electrical: 'electrical', sfp1g: 'sfp', sfp10g: 'sfpplus', sfp40g: 'qsfpplus', sfp100g: 'qsfp28'};
  const legacySpeeds = {electrical: 'unknown', sfp1g: '1g', sfp10g: '10g', sfp40g: '40g', sfp100g: '100g'};
  function normalizeSlot(slot) {
    return {...slot, connector: slot.connector || legacyConnectors[slot.capacity] || 'electrical',
      capacity: legacySpeeds[slot.capacity] || slot.capacity};
  }
  function slotType(slot) { return slot.connector ? connectors[slot.connector]?.type : capacities[slot.capacity]?.type; }
  function capacityValid(slot) {
    return slot.connector ? !!connectors[slot.connector] && (slot.capacity === 'unknown' || !!portCapacities[slot.capacity])
      : !!capacities[slot.capacity];
  }
  function maximumSpeed(value) {
    const rates = [...String(value || '').matchAll(/(\d+(?:\.\d+)?)\s*([mg])(?:bps|bit|base|[-_])/gi)]
      .map(match => Number(match[1]) * (match[2].toLowerCase() === 'g' ? 1000 : 1));
    return rates.length ? Math.max(...rates) : 0;
  }
  function capacityFromInterfaceName(name) {
    // Match native interface families, not arbitrary descriptions containing SFP.
    // QSFP values describe the physical cage, not an individual breakout lane.
    const families = [
      [/^qsfp56-dd-\d+(?:-\d+)*$/, '400g'],
      [/^qsfp56-\d+(?:-\d+)*$/, '200g'],
      [/^qsfp28-\d+(?:-\d+)*$/, '100g'],
      [/^qsfpplus-?\d+(?:-\d+)*$/, '40g'],
      [/^sfp56-\d+$/, '50g'],
      [/^sfp28-\d+$/, '25g'],
      [/^(?:sfp-sfpplus|sfpplus)\d+$/, '10g'],
      [/^sfp\d+$/, '1g']
    ];
    return families.find(([pattern]) => pattern.test(name))?.[1];
  }
  function discoveredConnector(hardware, optical, rate) {
    if (!optical) return 'electrical';
    if (/qsfp[-_]?dd[-_]?800/.test(hardware)) return 'qsfpdd800';
    if (/qsfp56-dd|qsfp[-_]?dd/.test(hardware)) return 'qsfpdd';
    if (/qsfp56/.test(hardware)) return 'qsfp56';
    if (/qsfp28/.test(hardware)) return 'qsfp28';
    if (/qsfp/.test(hardware)) return 'qsfpplus';
    if (/sfp56/.test(hardware)) return 'sfp56';
    if (/sfp28/.test(hardware)) return 'sfp28';
    if (/sfpplus|sfp\+/.test(hardware)) return 'sfpplus';
    return rate >= 800000 ? 'qsfpdd800' : rate >= 400000 ? 'qsfpdd' : rate >= 200000 ? 'qsfp56'
      : rate >= 100000 ? 'qsfp28' : rate >= 50000 ? 'sfp56' : rate >= 40000 ? 'qsfpplus'
      : rate >= 25000 ? 'sfp28' : rate >= 10000 ? 'sfpplus' : 'sfp';
  }
  const names = {rj45: 'RJ45', sfp: 'SFP', qsfp: 'QSFP+ 40G', wireless: 'Wireless'};
  function portsFor(previous, counts) {
    return types.flatMap(type => Array.from({length: counts[type]}, (_, index) =>
      previous.filter(port => port.type === type)[index] || {id: newId(), type}));
  }
  function slotsFor(node) {
    if (node.slots?.length) return node.slots.map(slot => ({...slot}));
    const defaults = {wireless: ['wireless', 'unknown'], rj45: ['ether', 'electrical'], sfp: ['sfp-sfpplus', 'sfp10g'], qsfp: ['qsfp', 'sfp40g']};
    return types.flatMap(type => {
      const members = (node.ports || []).filter(port => port.type === type);
      return members.length ? [{id: 'legacy-' + type, name: defaults[type][0], capacity: defaults[type][1], count: members.length, ...(type === 'wireless' ? {connector: 'wireless'} : {})}] : [];
    });
  }
  function portsForSlots(previous, slots) {
    if (slots.filter(slot => slot.connector === 'wireless').reduce((sum, slot) => sum + Number(slot.count), 0) > 1) return null;
    if (slots.some(slot => !slot.id || !slot.name.trim() || slot.name.length > 80 || !capacityValid(slot)
        || !Number.isInteger(slot.count) || slot.count < 1 || slot.count > 96)) return null;
    return slots.flatMap(slot => {
      const type = slotType(slot);
      const existing = previous.filter(port => port.slotId === slot.id ||
        (!port.slotId && slot.id === 'legacy-' + port.type));
      return Array.from({length: slot.count}, (_, index) => ({
        ...existing[index], id: existing[index]?.id || newId(), type, slotId: slot.id,
        name: slot.interfaceNames?.[index] || slot.name.trim() + (index + 1), capacity: slot.capacity,
        ...(slot.connector ? {connector: slot.connector} : {})
      }));
    });
  }
  function zabbixConnector(row, previous) {
    const name = (row['default-name'] || row.name || '').toLowerCase();
    const declared = String(row.connector || '').trim().toLowerCase();
    const connector = ({rj45: 'electrical', 'sfp+': 'sfpplus', 'qsfp+': 'qsfpplus'})[declared] || declared;
    if (Object.hasOwn(connectors, connector)) return {value: connector, identified: true};
    if (['71', '157'].includes(row['snmp-type']) || /^(?:wifi|wlan|ath)\d|^wireless$/.test(name))
      return {value: 'wireless', identified: true};
    if (/^(?:qsfp|sfp)/.test(name)) return {value: discoveredConnector(name, true, 0), identified: true};
    // The ASR physical TenGigabitEthernet representation uses the map's SFP+ library.
    if (/^tengigabitethernet\d/.test(name)) return {value: 'sfpplus', identified: true};
    if (/^(?:ether|eth)\d/.test(name)) return {value: 'electrical', identified: true};
    const saved = previous?.connector || legacyConnectors[previous?.capacity];
    return {value: saved || 'electrical', identified: false};
  }
  function importInterfaces(rows, previous) {
    const slots = [], ports = [], unidentifiedConnectors = [];
    for (const row of rows) {
      const name = row.name;
      if (!name || ports.some(port => port.name === name)) continue;
      const old = previous.find(port => port.name === name);
      const zabbix = row.source === 'zabbix' ? zabbixConnector(row, old) : null;
      if (zabbix && !zabbix.identified) unidentifiedConnectors.push(name);
      const hardware = (row['default-name'] || name).toLowerCase();
      const declaredConnector = Object.hasOwn(connectors, row.connector || '') ? row.connector : null;
      const optical = (declaredConnector != null && !['electrical', 'wireless'].includes(declaredConnector)) || /sfp|qsfp/.test(hardware) || row['sfp-supported'] === 'true' || 'sfp-module-present' in row;
      const speed = row.rate || row.speed || '';
      // Native SFP names identify cage capacity even without a transceiver.
      // Negotiated rate and configured speed never define physical capacity.
      const maximum = maximumSpeed(row.supported) || maximumSpeed(row['supported-rates'])
        || (!optical ? maximumSpeed(row.advertising) : 0);
      const connector = zabbix?.value || declaredConnector || discoveredConnector(hardware, optical, maximum);
      const opticalCapacity = zabbix && {sfp: '1g', sfpplus: '10g', sfp28: '25g', sfp56: '50g',
        qsfpplus: '40g', qsfp28: '100g', qsfp56: '200g', qsfpdd: '400g', qsfpdd800: '800g'}[connector];
      const capacity = capacityFromInterfaceName(hardware) || opticalCapacity
        || (zabbix ? (old?.capacity ? legacySpeeds[old.capacity] || old.capacity : null)
          : Object.keys(portCapacities).find(key => maximumSpeed(portCapacities[key]) === maximum)) || 'unknown';
      const interfaceName = row['default-name'] || name;
      const slotName = (interfaceName.replace(/\d+$/, '') || interfaceName).slice(0, 80);
      let slot = slots.find(slot => slot.name === slotName && slot.connector === connector && slot.capacity === capacity && slot.count < 96);
      if (!slot) {
        slot = {id: newId(), name: slotName, connector, capacity, count: 0, interfaceNames: []};
        slots.push(slot);
      }
      slot.count++; slot.interfaceNames.push(name);
      ports.push({id: old?.id || newId(), slotId: slot.id, name, capacity,
        connector, type: connectors[connector].type, comment: row.comment || '', interfaceType: row.type || 'ether',
        speed, running: row.disabled === 'true' ? false : row.status ? row.status === 'link-ok' : row.running == null ? null : row.running === 'true'});
    }
    if (!rows.some(row => row.source === 'zabbix')) return {slots, ports, unidentifiedConnectors};
    const orderedPorts = slots.flatMap(slot => {
      const members = ports.filter(port => port.slotId === slot.id)
        .sort((a, b) => interfaceNames.compare(a.name || '', b.name || ''));
      slot.interfaceNames = members.map(port => port.name);
      return members;
    });
    return {slots, ports: orderedPorts, unidentifiedConnectors};
  }
  function isEquipment(node) { return node.icon === 'switch' || ['radio', 'sector-radio'].includes(node.icon); }
  function geometry(node) {
    const ports = node.ports || [];
    const keys = [...new Set(ports.map(port => port.slotId || port.type))];
    const groups = keys.map(key => {
      const members = ports.filter(port => (port.slotId || port.type) === key)
        .sort((a, b) => interfaceNames.compare(a.name || '', b.name || ''));
      const singleRow = members.length <= 12;
      const columns = singleRow ? members.length : Math.ceil(members.length / 2);
      const pitch = 23;
      return {members, columns, singleRow, pitch, width: Math.max(0, columns - 1) * pitch + 24};
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
        const singleRow = group.singleRow;
        const column = singleRow ? index : Math.floor(index / 2);
        const row = index % 2;
        const rowGap = 25;
        positioned.push({...port, label: port.name ? port.name + ' · ' + (port.connector ? connectors[port.connector]?.label + (portCapacities[port.capacity] ? ' · ' + portCapacities[port.capacity] : '') : capacities[port.capacity]?.label || names[port.type]) + (port.comment ? ' · ' + port.comment : '') + (port.speed ? ' · Link: ' + port.speed : '') + (typeof port.running === 'boolean' ? (port.running ? ' · Link ativo' : ' · Sem link') : '') : names[port.type] + ' ' + (index + 1),
          x: left + 12 + column * group.pitch,
          y: centerY + (singleRow || row === 0 ? rowGap / 2 : -rowGap / 2),
          cableDirection: singleRow || row === 0 ? 1 : -1});
      });
      left += group.width + gap;
    }
    if (node.icon === 'sector-radio') {
      const wired = positioned.filter(port => port.connector !== 'wireless' && port.type !== 'wireless');
      return {width: 90, height: 184, ports: positioned.map(port =>
        port.connector === 'wireless' || port.type === 'wireless'
          ? {...port, x: 33, y: 0, cableDirection: 1}
          : {...port, x: -24 + (wired.indexOf(port) - (wired.length - 1) / 2) * 13.5, y: 54, visualScale: .55, cableDirection: 1})};
    }
    if (node.icon === 'radio') {
      const wired = positioned.filter(port => port.connector !== 'wireless' && port.type !== 'wireless');
      return {width: 180, height: 180, ports: positioned.slice(0, 4).map(port =>
        port.connector === 'wireless' || port.type === 'wireless'
          ? {...port, x: 49.5, y: -10.5, cableDirection: 1}
          : {...port, x: (wired.indexOf(port) - (wired.length - 1) / 2) * 13.5 - 36.4, y: 55, visualScale: .55, cableDirection: 1})};
    }
    return {width, height: 110, ports: positioned};
  }

  function portVisual(port) {
    if (!port) return null;
    if (port.connector) {
      const artwork = port.connector === 'wireless' ? 'wireless' : port.connector === 'electrical' ? 'electrical' : connectors[port.connector]?.type === 'sfp'
        ? 'sfp10g' : port.connector === 'qsfpplus' ? 'sfp40g' : 'sfp100g';
      return portArtwork[artwork] || null;
    }
    return portArtwork[port.capacity || {rj45: 'electrical', sfp: 'sfp10g', qsfp: 'sfp40g'}[port.type]] || null;
  }
  function connectedPorts(node, links) {
    const ports = new Set();
    for (const link of links) {
      if (link.from === node.id && link.fromPort) ports.add(link.fromPort);
      if (link.to === node.id && link.toPort) ports.add(link.toPort);
    }
    return ports;
  }
  function plugDirection(port) {
    return portVisual(port)?.upright ? 1 : port.cableDirection;
  }
  function cableDirection(node, portId) {
    if (!isEquipment(node) || node.interfacesEnabled === false) return 0;
    const port = geometry(node).ports.find(port => port.id === portId);
    return portVisual(port) ? plugDirection(port) : 0;
  }
  function transformPoint(node, x, y) {
    const scale = Math.max(.25, Math.min(3, Number(node.scale) || 1));
    const angle = (Number(node.rotation) || 0) * Math.PI / 180;
    x *= scale * (node.flipX ? -1 : 1); y *= scale;
    return {x: x * Math.cos(angle) - y * Math.sin(angle),
      y: x * Math.sin(angle) + y * Math.cos(angle)};
  }
  const serverImages = {
    'tower-server': {width: 70, height: 119.6, label: 'Servidor em torre'},
    'battery': {width: 140, height: 98.10, label: 'Bateria'},
    'vm-server': {width: 160, height: 48.75, label: 'VM Server'}
  };
  function bounds(node) {
    const shape = serverImages[node.icon] || (node.icon === 'cloud' ? {width: 152, height: 76}
      : node.icon === 'telecom-site' ? {width: 112, height: 106}
      : geometry(node.interfacesEnabled === false ? {...node, ports: []} : node));
    const top = serverImages[node.icon] ? -shape.height / 2 : node.icon === 'cloud' ? -38 : node.icon === 'telecom-site' ? -53 : node.icon === 'sector-radio' ? -92 : node.icon === 'radio' ? -90 : -55;
    const corners = [-shape.width / 2, shape.width / 2].flatMap(x =>
      [top, top + shape.height].map(y => transformPoint(node, x, y)));
    return {left: Math.min(...corners.map(p => p.x)), right: Math.max(...corners.map(p => p.x)),
      top: Math.min(...corners.map(p => p.y)), bottom: Math.max(...corners.map(p => p.y))};
  }
  function endpoint(node, portId) {
    const port = node.interfacesEnabled !== false && node.icon !== 'circle' && portId
      && geometry(node).ports.find(port => port.id === portId);
    const visual = isEquipment(node) && portVisual(port);
    const point = transformPoint(node, port ? port.x : 0,
      (port ? port.y : 0) + (visual ? plugDirection(port) * visual.socketY * (port.visualScale || 1) : 0));
    return {x: node.x + point.x, y: node.y + point.y};
  }
  function image(svg, parent, file, x, y, width, height, filter) {
    // Reuse the QSFP image for the neutral 40G frame instead of downloading it twice.
    if (file === 'qsfp40') {
      file = 'qsfp';
      const id = 'port-neutral-' + (++tintSequence);
      const defs = svg('defs', {}), neutral = svg('filter', {id});
      neutral.append(svg('feColorMatrix', {type: 'saturate', values: '0'}));
      defs.append(neutral); parent.append(defs); filter = 'url(#' + id + ')';
    }
    parent.append(svg('image', {href: '/map-assets/' + file + (file === 'sector-radio' ? '.webp?v=20261123-sector' : '.svg?v=20261120-optimized'), x, y, width, height,
      preserveAspectRatio: 'none', 'aria-hidden': 'true', 'pointer-events': 'none', ...(filter ? {filter} : {})}));
  }
  function pingIndicator(state) {
    return state === 'online' ? {color: '#58bd83', label: 'Online (ping)'}
      : state === 'offline' ? {color: '#ff0000', label: 'Offline (ping)'}
      : {color: '#687486', label: 'Sem dados recentes de ping'};
  }
  function draw(svg, group, node, fill, selected, options = {}) {
    const shape = geometry(node.interfacesEnabled === false ? {...node, ports: []} : node);
    const outer = group;
    if (node.rotation || node.flipX || (node.scale && node.scale !== 1)) {
      group = svg('g', {transform: `rotate(${Number(node.rotation) || 0}) scale(${(node.flipX ? -1 : 1) * (node.scale || 1)} ${node.scale || 1})`});
      outer.append(group);
    }
    let bodyFilter;
    if (!['radio', 'sector-radio'].includes(node.icon) && /^#[0-9a-fA-F]{6}$/.test(node.switchColor || '')) {
      const id = 'switch-tint-' + (++tintSequence);
      const channels = [1, 3, 5].map(offset => parseInt(node.switchColor.slice(offset, offset + 2), 16) / 255);
      const matrix = channels.flatMap(channel => [channel * 0.2126, channel * 0.7152, channel * 0.0722, 0, 0]);
      const defs = svg('defs', {}), filter = svg('filter', {id, 'color-interpolation-filters': 'sRGB'});
      filter.append(svg('feColorMatrix', {type: 'matrix', values: [...matrix, 0, 0, 0, 1, 0].join(' ')}));
      defs.append(filter); group.append(defs); bodyFilter = 'url(#' + id + ')';
    }
    const sector = node.icon === 'sector-radio';
    const radio = node.icon === 'radio' || sector;
    const top = sector ? -92 : radio ? -90 : -55;
    image(svg, group, sector ? 'sector-radio' : radio ? 'radio' : 'switch', -shape.width / 2, top, shape.width, shape.height, bodyFilter);
    group.append(svg('rect', {x: -shape.width / 2, y: top, width: shape.width, height: shape.height,
      rx: 6, fill: 'transparent', stroke: selected && !options.selectionFrame ? 'var(--cyan)' : 'none',
      'stroke-width': 1.5, 'vector-effect': 'non-scaling-stroke',
      'pointer-events': 'all'}));
    const status = pingIndicator(options.pingState);
    const led = svg('circle', {cx: sector ? -23.7 : radio ? -36.4 : -shape.width / 2 + shape.width * 0.0557, cy: sector ? 28.8 : radio ? 35.2 : 17, r: sector ? 2.7 : radio ? 1.6 : 3.5,
      fill: status.color, class: 'switch-status-led', 'data-ping-host': node.hostId || '',
      role: 'img', 'aria-label': status.label});
    const statusTitle = svg('title', {}); statusTitle.textContent = status.label;
    led.append(statusTitle); group.append(led);
    for (const port of shape.ports) {
      const visual = portVisual(port);
      const plugged = visual && options.connectedPorts?.has(port.id);
      const description = port.label + (plugged ? ' · Em uso' : '');
      const g = svg('g', {transform: `translate(${port.x} ${port.y})`, class: 'switch-port', 'data-port-id': port.id,
        ...(options.interactive ? {tabindex: 0, role: 'button', 'aria-label': description,
          'aria-pressed': String(options.origin === port.id)} : {})});
      const title = svg('title', {});
      title.textContent = description;
      g.append(title);
      if (sector && (port.connector === 'wireless' || port.type === 'wireless')) {
        // Invisible anchor; retain focus and hit target for editing.
      } else if (radio && (port.connector === 'wireless' || port.type === 'wireless')) {
        const mark = svg('g', {class: 'radio-wireless-indicator', fill: 'none', stroke: plugged ? '#7dd3fc' : '#55565a',
          'stroke-width': 1.1, 'stroke-linecap': 'round', 'data-wireless-connected': String(!!plugged)});
        mark.append(svg('circle', {cx: -2.7, cy: 0, r: .8, fill: plugged ? '#7dd3fc' : '#55565a', stroke: 'none'}));
        mark.append(svg('path', {d: 'M-.7 -2a3 3 0 0 1 0 4M1.1 -3.6a5.2 5.2 0 0 1 0 7.2M2.9 -5.1a7.3 7.3 0 0 1 0 10.2'}));
        g.append(mark);
      } else if (plugged) {
        const artwork = svg('g', {transform: `scale(${port.visualScale || 1})`});
        const module = svg('g', {class: 'switch-port-plug', transform: plugDirection(port) < 0 ? 'scale(1 -1)' : 'scale(1 1)'});
        image(svg, module, visual.occupied, -11, -10, 22, 19);
        if (port.visualScale) { artwork.append(module); g.append(artwork); } else g.append(module);
      } else {
        const artwork = port.visualScale ? svg('g', {transform: `scale(${port.visualScale})`}) : g;
        image(svg, artwork, visual?.empty || port.type, -11, -10, 22, 19);
        if (artwork !== g) g.append(artwork);
      }
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
    const box = bounds(node);
    const positions = {ABOVE: {x: 0, y: box.top - 12, 'text-anchor': 'middle'},
      BELOW: {x: 0, y: box.bottom + 19, 'text-anchor': 'middle'},
      LEFT: {x: box.left - 12, y: 0, 'text-anchor': 'end'},
      RIGHT: {x: box.right + 12, y: 0, 'text-anchor': 'start'}};
    const label = svg('text', positions[node.labelPosition] || positions.BELOW);
    label.textContent = node.name;
    outer.append(label);
  }
  return {serverImages, isEquipment, newId, importInterfaces, types, capacities, connectors, portCapacities, normalizeSlot, slotsFor, portsForSlots, portsFor, geometry, transformPoint, bounds, endpoint, portVisual, cableDirection, connectedPorts, pingIndicator, draw};
})();
if (typeof module !== 'undefined') module.exports = MapSwitch;
