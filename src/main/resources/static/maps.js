'use strict';

const canManageMaps = document.querySelector('meta[name="is-admin"]')?.content !== 'false';
const $ = id => document.getElementById(id);
const labels = ['Sem dados', 'Sem incidentes', 'Não classificado', 'Informação', 'Atenção', 'Média', 'Alta', 'Desastre'];
const colors = ['#687486', '#58bd83', '#a7a9c0', '#00bbd3', '#fced4e', '#ffa059', '#ff0000', '#ed4545'];
let maps = [], groups = [], current, editing = false, tool = "select", selected, selectedLink, drag;
let inventory = [], pendingNode, baseline;
const past = [], future = [];
const selection = new Set();
let marquee;
let connectionOrigin = null;
let configuredPorts = [];
let configuredSlots = [];
let interfaceLoadGeneration = 0;
let loadingInterfaces = false;
let configuredSwitchColor = null;
let configuredNodeId = null;
let configuredHostId = null;
let pendingLink = null, lastConnectionClick = null;
let suppressConnectionUntil = 0;
let savingConnection = false;
let states = new Map(), generation = 0, timer;
let serverLedSequence = 0;
let pingStates = new Map(), pingGeneration = 0, pingTimer;
let summaryUpdatedAt = 0, summaryFailed = false;
let latencySummaries = new Map();
let mapView = 'incidents', metricStates = new Map(), metricRange;
const metricColors = {NORMAL:'#58bd83', ATENCAO:'#fced4e', CRITICO:'#ff0000'};
const metricLabels = {NORMAL:'Normal', ATENCAO:'Atenção', CRITICO:'Crítico'};
function metricSummary(node) {
  if (mapView === 'incidents') return null;
  const metric = metricStates.get((node.hostId ? 'host:' + node.hostId : 'group:' + node.groupId));
  const value = metric?.[mapView];
  const state = metric?.[mapView + 'State'];
  const label = mapView === 'latency' ? 'Latência média' : 'Perda de pacote';
  const formatted = value == null ? 'Sem dados' : value.toLocaleString('pt-BR', {maximumFractionDigits:2}) + (mapView === 'latency' ? ' ms' : '%');
  return {view:mapView, current:mapView === 'loss' ? metric?.lossSummary : (typeof latencySummaries === 'undefined' ? null : latencySummaries.get(node.hostId ? 'host:' + node.hostId : 'group:' + node.groupId)), label, formatted, state:metricLabels[state] || 'Sem dados', color:metricColors[state] || colors[0],
    detailsUrl: '/disponibilidade?' + new URLSearchParams({
      minutes: $('map-period').value,
      groupId: node.groupId || (typeof inventory === 'undefined' ? [] :
        inventory.find(host => host.id === node.hostId)?.groups || []).map(group => group.id).join(',') || '__none__'
    }),
    period: $('map-period').selectedOptions[0]?.textContent || '',
    range: metricRange ? new Date(metricRange.from).toLocaleString('pt-BR') + ' — ' + new Date(metricRange.to).toLocaleString('pt-BR') : ''};
}
const mapPeriodStorageKey = 'routiva.maps.period';
function restoreMapPeriod() {
  const control = $('map-period');
  let saved;
  try { saved = localStorage.getItem(mapPeriodStorageKey); } catch { /* Storage may be unavailable. */ }
  control.value = [...control.options].some(option => option.value === saved) ? saved : '1440';
}
function saveMapPeriod() {
  try { localStorage.setItem(mapPeriodStorageKey, $('map-period').value); } catch { /* Keep filters usable without storage. */ }
}
function changeMapQuery() {
  mapView = $('map-view').value;
  saveMapPeriod();
  $('map-period-field').hidden = mapView === 'incidents';
  metricStates.clear();
  metricRange = null;
  states.clear();
  summaryUpdatedAt = 0;
  summaryFailed = false;
  popSummary.close();
  draw();
  updateMapOverview();
  refresh(++generation);
}
function updateMapOverview() {
  renderMapOverview({nodes:current?.layout.nodes || [], view:mapView,
    stateOf: node => mapView === 'incidents' ? groupLevel(nodeHosts(node))
      : metricStates.get(node.hostId ? 'host:' + node.hostId : 'group:' + node.groupId)?.[mapView + 'State'],
    updatedAt:summaryUpdatedAt, failed:summaryFailed, busy:$('refresh-map').disabled, colors, labels});
}
restoreMapPeriod();
$('map-view').onchange = $('map-period').onchange = changeMapQuery;
document.querySelectorAll('[data-map-view]').forEach(button => {
  button.onclick = () => {
    if ($('map-view').value === button.dataset.mapView) return;
    $('map-view').value = button.dataset.mapView;
    changeMapQuery();
  };
});
const popSummary = new PopSummary({
  stage: $('map-stage'), hosts: nodeHosts, metric: metricSummary,
  minimumSeverity: () => current?.layout.minimumSeverity ?? 0,
  showAcknowledged: () => current?.layout.showAcknowledged ?? true,
  groupName: id => groups.find(group => group.id === id)?.name || id,
  freshness: () => ({updatedAt: summaryUpdatedAt, failed: summaryFailed}), colors, labels
});

async function api(path, body) {
  const options = body === undefined ? {} : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
  };
  const response = await fetch('/api/maps' + path, options);
  if (!response.ok) throw new Error(response.status === 409
    ? 'Outra edição foi salva. Recarregue a página para abrir a versão atual.'
    : 'Falha ao consultar ou salvar os dados.');
  return response.json();
}

function svg(tag, attributes) {
  const el = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [key, value] of Object.entries(attributes)) el.setAttribute(key, value);
  return el;
}

function hostLevel(host, minimumSeverity = current?.layout.minimumSeverity ?? 0) {
  if (!host.incidents) return -2;
  const level = host.incidents.reduce((level, issue) => issue.severity >= minimumSeverity && (current?.layout.showAcknowledged !== false || !issue.acknowledged) ? Math.max(level, issue.severity) : level, -1);
  return level === -1 && host.monitoring && host.monitoring !== 'monitored' ? -2 : level;
}

function groupLevel(hosts, minimumSeverity = current?.layout.minimumSeverity ?? 0) {
  if (!hosts.length) return -2;
  const levels = hosts.map(host => hostLevel(host, minimumSeverity));
  const worst = Math.max(...levels);
  return worst === -1 && levels.includes(-2) ? -2 : worst;
}

function checkpoint() {
  current.layout.nodes.forEach(ensureElementOrigin);
  past.push(JSON.stringify(current.layout));
  if (past.length > 100) past.shift();
  future.length = 0;
}
function restore(source, destination) {
  if (!source.length) return;
  destination.push(JSON.stringify(current.layout));
  current.layout = JSON.parse(source.pop());
  connectionOrigin = null;
  selection.clear();
  selected = selectedLink = drag = null;
  draw();
}
function transformState(node) {
  return {rotation: node.rotation || 0, scale: node.scale || 1, flipX: !!node.flipX};
}
function cancelTransform() {
  if (drag?.kind !== 'transform') return false;
  Object.assign(drag.node, drag.initial);
  if (drag.moved) { past.pop(); future.splice(0, future.length, ...drag.future); }
  drag = null; draw(); return true;
}
function ensureElementOrigin(node) {
  node.origin ||= {x: node.x, y: node.y, scale: node.scale || 1};
}
function applyElementAction(node, action) {
  ensureElementOrigin(node);
  checkpoint();
  if (action === 'flipX') node.flipX = !node.flipX;
  if (action === 'reset') Object.assign(node, {scale: 1, rotation: 0, flipX: false});
  draw();
  $('canvas').querySelector(`[data-transform="${action}"]`)?.focus();
}
function drawTransformHandles(canvas, node) {
  MapElementControls.draw({canvas, node, box: MapSwitch.bounds(node), scale: camera.scale,
    active: drag?.kind === 'transform' ? drag.action : null, svg,
    onAction: action => applyElementAction(node, action),
    onStart: (action, event) => {
      ensureElementOrigin(node);
      drag = {kind: 'transform', action, node, pointer: event.pointerId,
        initial: transformState(node), future: [...future], start: canvasPoint(event),
        x: event.clientX, y: event.clientY, moved: false};
      canvas.setPointerCapture(event.pointerId);
      if (action === 'scale') draw();
    },
    onAdjust: (action, direction, snap) => {
      ensureElementOrigin(node); checkpoint();
      if (action === 'rotation') node.rotation = ((node.rotation || 0) + direction * (snap ? 15 : 1) + 360) % 360;
      if (action === 'scale') node.scale = Math.max(.25, Math.min(3, (node.scale || 1) + direction * .05));
      draw(); canvas.querySelector(`[data-transform="${action}"]`)?.focus();
    }
  });
}
function nodeHosts(node) {
  return states.get(node.hostId ? 'host:' + node.hostId : node.groupId) || [];
}

const labelPositions = {
  ABOVE: {x:0, y:-19, 'text-anchor':'middle'},
  BELOW: {x:0, y:29, 'text-anchor':'middle'},
  LEFT: {x:-19, y:0, 'text-anchor':'end', 'dominant-baseline':'central'},
  RIGHT: {x:19, y:0, 'text-anchor':'start', 'dominant-baseline':'central'}
};

function drawNode(group, name, position, fill, isSelected = false) {
  group.append(svg('circle', {r:12, fill, stroke:isSelected ? 'var(--cyan)' : 'none',
    'stroke-width':3}));
  if (name == null) return;
  const label = svg('text', labelPositions[position] || labelPositions.BELOW);
  label.textContent = name;
  group.append(label);
}

function drawTelecomSite(group, name, position, fill, isSelected = false, node = {}) {
  const outer = group;
  group = svg('g', {transform: `rotate(${node.rotation || 0}) scale(${(node.flipX ? -1 : 1) * (node.scale || 1)} ${node.scale || 1})`});
  outer.append(group);
  const cloud = node.icon === 'cloud';
  const server = MapSwitch.serverImages?.[node.icon];
  group.append(svg('image', server
    ? {href: `/map-assets/${node.icon}.webp?v=20261120-server-cutouts`, x: -server.width / 2, y: -server.height / 2, width: server.width, height: server.height}
    : cloud
    ? {href: '/map-assets/cloud.webp?v=20261120-optimized', x: -103, y: -77, width: 206, height: 154}
    : {href: '/map-assets/telecom-site.webp?v=20261120-optimized', x: -96, y: -64, width: 192, height: 128}));
  if (isSelected) group.append(svg('rect', {x: server ? -server.width / 2 : cloud ? -76 : -56, y: server ? -server.height / 2 : cloud ? -38 : -53, width: server ? server.width : cloud ? 152 : 110, height: server ? server.height : cloud ? 76 : 104,
    rx: 6, fill: 'none', stroke: 'var(--cyan)', 'stroke-width': 2}));
  if (server) {
    const vm = node.icon === 'vm-server';
    const battery = node.icon === 'battery';
    const cx = battery ? 48 : vm ? -67.3 : 22, cy = battery ? -4 : vm ? 7.9 : -33;
    if (vm) {
      // Neutralize the baked-in green light and its halo before drawing the live LED.
      const id = 'server-led-' + (++serverLedSequence);
      const defs = svg('defs', {}), clip = svg('clipPath', {id: id + '-clip'});
      clip.append(svg('circle', {cx, cy, r: 7}));
      const filter = svg('filter', {id: id + '-neutral'});
      filter.append(svg('feColorMatrix', {type: 'saturate', values: '0'}));
      defs.append(clip, filter); group.append(defs);
      group.append(svg('image', {href: '/map-assets/vm-server.webp?v=20261120-server-cutouts',
        x: -server.width / 2, y: -server.height / 2, width: server.width, height: server.height,
        'clip-path': `url(#${id}-clip)`, filter: `url(#${id}-neutral)`}));
    }
    const status = serverStatusFor(node);
    const led = svg('circle', {cx, cy, r: vm ? 3 : 2.5, fill: status.color,
      stroke: '#344052', 'stroke-width': .6, class: 'server-status-led',
      'data-server-node': node.id || '', role: 'img', 'aria-label': status.label});
    const title = svg('title', {}); title.textContent = status.label;
    led.append(title); group.append(led);
  } else if (!editing) group.append(svg('circle', {cx: 0, cy: cloud ? 30 : 48, r: 5, fill, stroke: 'var(--panel-bg)', 'stroke-width': 2}));
  if (name == null) return;
  const box = MapSwitch.bounds({...node, icon: server ? node.icon : cloud ? 'cloud' : 'telecom-site'});
  const positions = {ABOVE: {x: 0, y: box.top - 8, 'text-anchor': 'middle'},
    BELOW: {x: 0, y: box.bottom + 17, 'text-anchor': 'middle'},
    LEFT: {x: box.left - 9, y: 0, 'text-anchor': 'end'},
    RIGHT: {x: box.right + 9, y: 0, 'text-anchor': 'start'}};
  const label = svg('text', positions[position] || positions.BELOW);
  label.textContent = name;
  outer.append(label);
}

const popStyles = {
  normal: '#00efab', attention: '#ffd318', critical: '#ff515c',
  information: '#00cfee', unclassified: '#b5c6d7'
};

function popStatus(level, metric, preview = false) {
  if (preview) return 'normal';
  if (metric) return ({Normal: 'normal', Atenção: 'attention', Crítico: 'critical'})[metric.state] || 'unclassified';
  if (level === -1) return 'normal';
  if (level === 1) return 'information';
  if (level >= 4) return 'critical';
  if (level >= 2) return 'attention';
  return 'unclassified';
}

function drawPop(group, node, status = 'normal', selected = false, showLabel = true) {
  const scale = node.scale || 1;
  const color = popStyles[status];
  const art = svg('g', {transform: `rotate(${node.rotation || 0}) scale(${node.flipX ? -scale : scale} ${scale})`});
  art.append(svg('image', {href: '/map-assets/pop-' + status + '.svg?v=20261125-pop',
    x: -50, y: -50, width: 100, height: 100, 'aria-hidden': 'true'}));
  if (selected) art.append(svg('rect', {x: -50, y: -50, width: 100, height: 100,
    rx: 6, fill: 'none', stroke: 'var(--cyan)', 'stroke-width': 1.5}));
  group.append(art);
  if (!showLabel || !node.name) return;
  const box = MapSwitch.bounds(node);
  const width = Math.max(92, Array.from(node.name).length * 9 + 20);
  const y = box.bottom - 3 * scale;
  const height = 26;
  group.append(svg('rect', {x: -width / 2, y, width, height, rx: height / 2,
    fill: color, opacity: .18, stroke: color, 'stroke-width': 5}));
  group.append(svg('rect', {x: -width / 2, y, width, height, rx: height / 2,
    fill: '#08151e', stroke: color, 'stroke-width': 2}));
  const label = svg('text', {x: 0, y: y + height / 2, 'text-anchor': 'middle',
    'dominant-baseline': 'central', style: 'font-size:14px;font-weight:700'});
  label.textContent = node.name;
  group.append(label);
}

function drawMapElement(group, node, color, selected, options = {}) {
  if (node.icon === 'pop') {
    drawPop(group, node, options.popStatus, selected, options.showLabel !== false);
    return;
  }
  if (MapSwitch.isEquipment(node)) {
    MapSwitch.draw(svg, group, node, color, selected, options);
    return;
  }
  const renderer = ['telecom-site', 'cloud', 'tower-server', 'vm-server', 'battery'].includes(node.icon)
    ? drawTelecomSite : drawNode;
  renderer(group, options.showLabel === false ? null : node.name, node.labelPosition, color, selected, node);
}

function alignDraggedNode(node, x, y, nodes, scale) {
  const peers = nodes.filter(other => other.id !== node.id);
  const tolerance = 6 / scale;
  const guides = [];
  const position = {x:Math.round(x), y:Math.round(y)};
  for (const axis of ['x', 'y']) {
    const nearest = peers.reduce((best, other) =>
      Math.abs(other[axis] - position[axis]) <= tolerance &&
      (!best || Math.abs(other[axis] - position[axis]) < Math.abs(best[axis] - position[axis]))
        ? other : best, null);
    if (!nearest) continue;
    position[axis] = nearest[axis];
    guides.push(axis);
  }
  Object.assign(node, position);
  return guides.map(axis => {
    const otherAxis = axis === 'x' ? 'y' : 'x';
    const aligned = peers.filter(other => other[axis] === node[axis]);
    const values = [node[otherAxis], ...aligned.map(other => other[otherAxis])];
    const from = Math.min(...values) - 24 / scale;
    const to = Math.max(...values) + 24 / scale;
    return axis === 'x'
      ? {x1:node.x, y1:from, x2:node.x, y2:to}
      : {x1:from, y1:node.y, x2:to, y2:node.y};
  });
}

function draw() {
  if (typeof MapElementControls !== 'undefined') MapElementControls.hideTooltip();
  if (typeof MapConnectionTooltip !== 'undefined') MapConnectionTooltip.hide();
  const canvas = $('canvas');
  const focusedPort = document.activeElement?.getAttribute('data-port-id');
  for (const menu of draw.hostMenus || []) menu.remove();
  draw.hostMenus = [];
  canvas.replaceChildren();
  if (!current) return;
  const { nodes, links } = current.layout;
  connectionDefinitions = links.map(link => link.connection).filter(Boolean);
  $('map-title').textContent = (editing ? 'Editar mapa — ' : '') + current.layout.name;
  canvas.dataset.tool = editing ? tool : '';
  if (editing) {
    for (const [id, mode] of [['connect','connect'], ['select-tool','select'], ['pan-tool','pan']])
      $(id).setAttribute('aria-pressed', String(tool === mode));
    $('undo').disabled = !past.length;
    $('redo').disabled = !future.length;
    $('delete-item').disabled = !selection.size && !selected && !selectedLink;
    $('editor-node-count').textContent = nodes.length + ' elementos';
    $('editor-link-count').textContent = links.length + ' conexões';
    $('hint').textContent = tool === 'connect' ? (connectionOrigin ? 'Selecione a porta de destino. Esc cancela.' : 'Selecione uma porta ou elemento de origem. Esc cancela.')
      : tool === 'pan' ? 'Arraste para mover o mapa.' : 'Arraste no fundo para selecionar vários. Shift, Ctrl ou ⌘ + clique adiciona ou remove. Arraste a seleção para mover.';
  }
  for (const guide of drag?.guides || []) {
    canvas.append(svg('line', {...guide, class:'alignment-guide', 'aria-hidden':'true'}));
  }
  const connectors = svg('g', {class: 'map-connectors'});
  const byId = new Map(nodes.map(n => [n.id, n]));
  const trafficLayers = [];
  const animatedLinks = !editing ? links.filter(link => link.appearance?.endsWith('-pulses') ||
    (!link.appearance && link.mappedConnectionId)) : [];
  const pulsePhases = animatedLinks.length ? MapTraffic.pulsePhases(animatedLinks) : new Map();
  let selectedRoute = null;
  const linkLayer = link => link === selectedLink ? 2 : link.mappedConnectionId ? 1 : 0;
  for (const link of [...links].sort((a, b) => linkLayer(a) - linkLayer(b))) {
    const a = byId.get(link.from), b = byId.get(link.to);
    if (!a || !b) continue;
    const start = MapSwitch.endpoint(a, link.fromPort), end = MapSwitch.endpoint(b, link.toPort);
    const d = MapLinks.path(link, start, end);
    const wireless = link.appearance ? link.appearance.startsWith('dashed') : MapTraffic.isWireless(link, nodes);
    const pulses = link.appearance?.endsWith('-pulses');
    const legacyMapped = !link.appearance && link.mappedConnectionId;
    const line = svg('path', {d, class: 'map-connector' +
      (pulses || legacyMapped ? ' map-connector-mapped' : '') +
      (wireless ? ' map-connector-wireless' : '') +
      (link.appearance === 'solid-pulses' ? ' map-connector-solid-pulses' : '')});
    line.classList.toggle('selected-link', !editing && selectedLink === link);
    line.setAttribute('tabindex', 0);
    line.setAttribute('role', 'button');
    line.setAttribute('aria-label', 'Conexão entre ' + a.name + ' e ' + b.name);
    line.setAttribute('aria-pressed', String(selectedLink === link));
    line.onclick = event => {
      event?.stopPropagation();
      selectedLink = selectedLink === link ? null : link;
      selected = null; selection.clear(); draw();
    };
    line.onkeydown = event => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); line.onclick(event); }
    };
    if (editing && tool === 'select') {
      line.onpointerdown = event => {
        if (event.button !== 0) return;
        event.stopPropagation();
        if (link.style !== 'corners') return;
        drag = {kind: 'new-bend', link, pointer: event.pointerId, x: event.clientX, y: event.clientY,
          start: canvasPoint(event), moved: false};
      };
      line.setAttribute('tabindex', 0);
      line.setAttribute('role', 'button');
      line.setAttribute('aria-label', 'Conexão entre ' + a.name + ' e ' + b.name);
      line.onclick = () => {
        const now = Date.now();
        if (now < suppressConnectionUntil) return;
        if (lastConnectionClick?.link === link && now - lastConnectionClick.at < 400) {
          lastConnectionClick = null; openLinkDialog(link); return;
        }
        lastConnectionClick = {link, at: now};
        if (!editing) return;
        selectedLink = selectedLink === link ? null : link;
        selected = null; selection.clear(); draw();
      };
      line.onkeydown = event => {
        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); openLinkDialog(link); }
      };
      line.ondblclick = event => {
        event.preventDefault(); event.stopPropagation();
        if (Date.now() >= suppressConnectionUntil) openLinkDialog(link);
      };
    }
    const hit = svg('path', {d: line.getAttribute('d'), class: 'map-connector-hit',
      fill: 'none', stroke: 'transparent', 'stroke-width': 16, 'vector-effect': 'non-scaling-stroke',
      'aria-hidden': 'true'});
    for (const handler of ['onpointerdown', 'onclick', 'ondblclick']) hit[handler] = line[handler];
    if (link.mappedConnectionId && !editing) {
      const tooltipData = () => mappedConnectionTooltip(link);
      for (const path of [line, hit]) {
        path.dataset.connectionTooltip = link.mappedConnectionId;
        path.onpointermove = event => MapConnectionTooltip.scheduleShow(link.mappedConnectionId, path,
          {x:event.clientX, y:event.clientY}, tooltipData());
        path.onpointerleave = event => {
          if (event.relatedTarget?.dataset?.connectionTooltip !== link.mappedConnectionId) MapConnectionTooltip.hide();
        };
        path.onfocus = () => {
          const rect = path.getBoundingClientRect();
          MapConnectionTooltip.show(link.mappedConnectionId, path, {x:rect.x + rect.width / 2, y:rect.y + rect.height / 2}, tooltipData());
        };
        path.onblur = () => MapConnectionTooltip.hide();
      }
    }
    if (!editing && selectedLink === link) {
      selectedRoute = d;
      connectors.append(svg('path', {d, class: 'map-connector-selection-border' +
        (wireless ? ' map-connector-wireless' : ''), 'aria-hidden': 'true'}));
    }
    connectors.append(line, hit);
    if ((pulses || legacyMapped) && !editing) {
      const layer = svg('g', {class: 'map-traffic', 'aria-hidden': 'true'});
      connectors.append(layer);
      layer.trafficContext = {line, link, wireless, selected: selectedLink === link, phase: pulsePhases.get(link), route: MapLinks.points(link, start, end), nodes};
      updateTrafficLayer(layer);
      trafficLayers.push(layer);
    }
  }
  if (trafficLayers.length > 1 || (selectedRoute && trafficLayers.length))
    MapTraffic.maskOverlaps(svg, connectors, trafficLayers, selectedRoute);
  // Keep central anchors hidden, but leave cables visible over equipment with ports.
  const foregroundElements = [];
  for (const n of nodes) {
    const monitored = !editing && (n.hostId || n.groupId);
    const level = monitored ? groupLevel(nodeHosts(n)) + 2 : 1;
    const metric = monitored ? metricSummary(n) : null;
    const description = metric ? `${n.name} · ${metric.label}: ${metric.formatted} · ${metric.state}` : monitored ? n.name + ' · ' + labels[level] : n.name;
    const g = svg('g', {transform:'translate(' + n.x + ' ' + n.y + ')',
      ...(!editing && (n.hostId || n.groupId) ? {tabindex:0, role:'button'} : editing ? {tabindex:0, role:'button', 'aria-pressed':String(selection.has(n.id) || selected === n.id)} : {role:'img'}), 'aria-label':description});
    const title = svg('title', {});
    title.textContent = description;
    g.append(title);
    const equipment = MapSwitch.isEquipment(n);
    const color = metric ? metric.color : equipment ? colors[level]
      : !monitored && n.color ? n.color : editing || monitored ? colors[level] : colors[0];
    drawMapElement(g, n, color, selection.has(n.id) || selected === n.id, equipment ? {
      selectionFrame: editing && tool === 'select' && selection.size === 1,
      connectedPorts: MapSwitch.connectedPorts(n, links),
      pingState: editing ? 'unknown' : pingStateFor(n.hostId),
      interactive: editing, connect: tool === 'connect', origin: connectionOrigin?.nodeId === n.id ? connectionOrigin.portId : null,
      onPort: portId => connectEndpoint(n, portId)
    } : n.icon === 'pop' ? {popStatus: popStatus(monitored ? level - 2 : -2, metric, editing)} : undefined);
    g.onkeydown = event => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        if (!editing && (n.hostId || n.groupId)) g.dispatchEvent(new MouseEvent('click', {bubbles: true}));
        else choose(n, event.shiftKey || event.ctrlKey || event.metaKey);
      }
    };
    g.onpointerdown = event => {
      if (event.button !== 0 || (editing && tool === 'pan')) return;
      if (!editing && n.groupId) { event.stopPropagation(); return; }
      if (editing && tool === 'select') {
        const additive = event.shiftKey || event.ctrlKey || event.metaKey;
        const wasSingle = selection.size === 1 && selection.has(n.id);
        if (additive) {
          choose(n, true);
          return;
        }
        if (!selection.has(n.id)) {
          selection.clear();
          selection.add(n.id);
        }
        selected = n.id;
        selectedLink = null;
        canvas.setPointerCapture(event.pointerId);
        const point = new DOMPoint(event.clientX,event.clientY).matrixTransform(canvas.getScreenCTM().inverse());
        drag = {node:n, dx:n.x-point.x, dy:n.y-point.y, pointer:event.pointerId, x:event.clientX, y:event.clientY, moved:false, wasSingle,
          positions:current.layout.nodes.filter(node => selection.has(node.id))
            .map(node => ({node, x:node.x, y:node.y}))};
        draw();
        return;
      }
      choose(n);
    };
    if (!editing && n.hostId) {
      const host = inventory.find(host => host.id === n.hostId);
      draw.hostMenus.push(window.HostMenu.attach(g, $('workspace'), {ip: host?.ip, hostId: n.hostId}));
    }
    if (!editing && n.groupId) {
      draw.hostMenus.push(window.HostMenu.attach(g, $('workspace'), {
        groupId: n.groupId,
        onSubmap: n.subMapId ? () => choose(n) : undefined,
        onOpen: () => popSummary.close()
      }));
    }
    if (monitored) popSummary.bind(g, n);
    if (['switch', 'radio', 'sector-radio'].includes(n.icon) && n.interfacesEnabled !== false && n.ports?.length)
      canvas.append(g);
    else foregroundElements.push(g);
  }
  canvas.append(connectors);
  for (const element of foregroundElements) canvas.append(element);
  if (trafficLayers.length) MapTraffic.synchronizePulses(connectors);
  if (editing && tool === 'select') for (const [linkIndex, link] of links.entries()) {
    if (link.style !== 'corners') continue;
    const route = linkRoute(link);
    (link.points || []).forEach((point, index) => {
      const d = MapLinks.bendPath(link, route[0], route[route.length - 1], index, camera.scale);
      const mark = svg('path', {d, class: 'map-bend-mark', 'aria-hidden': 'true'});
      const handle = svg('path', {d,
        class: 'map-link-handle', tabindex: 0, role: 'button', 'data-bend': linkIndex + '-' + index,
        'aria-label': 'Canto ' + (index + 1) + '. Setas movem; Delete remove.'});
      handle.onpointerdown = event => {
        if (event.button !== 0) return;
        event.stopPropagation();
        lastConnectionClick = null;
        selectedLink = link; selected = null; selection.clear();
        const start = canvasPoint(event);
        drag = {kind: 'bend', link, index, pointer: event.pointerId, moved: false, dx: point.x - start.x, dy: point.y - start.y};
      };
      handle.onclick = event => { event.stopPropagation(); lastConnectionClick = null; };
      handle.ondblclick = event => {
        event.preventDefault?.(); event.stopPropagation();
        lastConnectionClick = null; suppressConnectionUntil = Date.now() + 400;
        checkpoint(); link.points.splice(index, 1); draw();
      };
      handle.onkeydown = event => {
        const step = event.shiftKey ? 10 : 1;
        const directions = {ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step]};
        if (event.key === 'Delete' || event.key === 'Backspace') {
          event.preventDefault(); event.stopPropagation(); checkpoint(); link.points.splice(index, 1); draw();
        } else if (directions[event.key]) {
          event.preventDefault(); event.stopPropagation(); checkpoint();
          const [dx, dy] = directions[event.key]; point.x += dx; point.y += dy; draw();
          canvas.querySelector('[data-bend="' + linkIndex + '-' + index + '"]')?.focus();
        }
      };
      canvas.append(mark, handle);
    });
  }
  if (focusedPort) canvas.querySelector('[data-port-id="' + CSS.escape(focusedPort) + '"]')?.focus();
  if (editing && tool === 'select' && selection.size === 1) {
    const node = nodes.find(n => selection.has(n.id));
    if (node && ['switch', 'radio', 'sector-radio', 'telecom-site', 'cloud', 'tower-server', 'vm-server', 'battery', 'pop'].includes(node.icon)) drawTransformHandles(canvas, node);
  }
  if (marquee) {
    canvas.append(svg('rect', {x:Math.min(marquee.start.x, marquee.end.x),
      y:Math.min(marquee.start.y, marquee.end.y),
      width:Math.abs(marquee.end.x - marquee.start.x),
      height:Math.abs(marquee.end.y - marquee.start.y), class:'selection-box', 'aria-hidden':'true'}));
  }
}

function choose(node, additive = false) {
  if (!editing && node.groupId && node.subMapId) {
    const target = maps.find(map => map.id === node.subMapId);
    if (!target) { window.notify('Submapa indisponível.'); return; }
    openMap(target, false, [...mapParents, current.id]);
    setMapExpanded(true);
    fitToView();
    return;
  }
  if (!editing || tool === 'pan') return;
  if (tool === 'connect') {
    if (!['switch', 'radio', 'sector-radio'].includes(node.icon) || node.interfacesEnabled === false || !node.ports?.length) connectEndpoint(node, null);
    return;
  }
  const origin = selected;
  if (tool === 'select' && additive) {
    if (selection.has(node.id)) selection.delete(node.id);
    else selection.add(node.id);
    selected = selection.values().next().value || null;
    selectedLink = null;
    draw();
    return;
  }
  const wasSingle = selection.size === 1 && selection.has(node.id);
  selection.clear();
  selection.add(node.id);
  selected = node.id;
  selectedLink = null;
  if (editing && tool === 'select' && wasSingle && origin === node.id) {
    openElement(node);
  }
  draw();
}

const viewport = $('viewport');
const minZoom = 0.1;
const maxZoom = 3;
let camera = {x:0, y:0, scale:1};
let followingFit = false, pan = null, animation = 0;

function resizeCanvas() {
  const canvas = $('canvas');
  const scale = camera.scale;
  const width = viewport.clientWidth / scale, height = viewport.clientHeight / scale;
  const x = camera.x - width / 2, y = camera.y - height / 2;
  canvas.setAttribute('viewBox', [x, y, width, height].join(' '));
  canvas.style.width = width * scale + 'px';
  canvas.style.height = height * scale + 'px';
  if (editing && current && canvas.querySelector('.map-transform-handle')) {
    MapElementControls.hideTooltip();
    canvas.querySelectorAll('.map-transform-handle, .map-selection-border, .map-scale-indicator').forEach(handle => handle.remove());
    const node = current.layout.nodes.find(n => selection.has(n.id));
    if (node) drawTransformHandles(canvas, node);
  }
}

function syncZoom() {
  $('zoom-in').disabled = camera.scale >= maxZoom;
  $('zoom-out').disabled = camera.scale <= minZoom;
}

function stopMotion() {
  cancelAnimationFrame(animation);
  followingFit = false;
}

function fitToView(smooth = false) {
  stopMotion();
  followingFit = true;
  const w = viewport.clientWidth, h = viewport.clientHeight;
  if (!w || !h) return;
  let target = {x:w / 2, y:h / 2, scale:1};
  if (current.layout.nodes.length) {
    const box = $('canvas').getBBox();
    const padding = Math.min(56, w / 5, h / 5);
    const verticalPadding = padding / 2;
    target = {
      x:box.x + box.width / 2, y:box.y + box.height / 2,
      scale:Math.min(maxZoom, (w - 2 * padding) / (box.width + 4), (h - 2 * verticalPadding) / (box.height + 4))
    };
  }
  const start = {...camera}, since = performance.now();
  function frame(now) {
    const t = smooth ? Math.min(1, (now - since) / 180) : 1;
    const ease = 1 - (1 - t) ** 3;
    camera = t === 1 ? target : {
      x:start.x + (target.x - start.x) * ease, y:start.y + (target.y - start.y) * ease,
      scale:start.scale + (target.scale - start.scale) * ease
    };
    resizeCanvas();
    if (t < 1) animation = requestAnimationFrame(frame);
    else syncZoom();
  }
  frame(since);
}
$('fit').onclick = () => fitToView(!matchMedia('(prefers-reduced-motion: reduce)').matches);
function changeZoom(scale, anchor = null) {
  stopMotion();
  if (anchor) {
    const ratio = camera.scale / scale;
    camera.x = anchor.x + (camera.x - anchor.x) * ratio;
    camera.y = anchor.y + (camera.y - anchor.y) * ratio;
  }
  camera.scale = scale;
  syncZoom();
  resizeCanvas();
}
function stepZoom(delta) {
  const scale = Number((camera.scale + delta).toFixed(10));
  changeZoom(Math.max(minZoom, Math.min(maxZoom, scale)));
}
$('zoom-in').onclick = () => stepZoom(0.1);
$('zoom-out').onclick = () => stepZoom(-0.1);
function wheelZoom(event) {
  if (!current) return;
  event.preventDefault();
  if (drag || pan || marquee || !event.deltaY) return;
  const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? viewport.clientHeight : 1;
  const delta = event.deltaY * unit;
  const lowerLimit = Math.min(minZoom, camera.scale);
  const scale = Math.max(lowerLimit, Math.min(maxZoom, camera.scale * Math.exp(-delta * 0.002)));
  if (scale === camera.scale) return;
  const anchor = canvasPoint(event);
  popSummary.close();
  MapConnectionTooltip.hide();
  changeZoom(scale, anchor);
}
viewport.addEventListener('wheel', wheelZoom, {passive: false});
new ResizeObserver(() => {
  if (current && viewport.clientWidth) {
    if (followingFit) fitToView(); else resizeCanvas();
  }
}).observe(viewport);
function syncMapSummary() {
  const stage = $('map-stage');
  const expanded = $('workspace').classList.contains('map-expanded') ||
    document.fullscreenElement === stage;
  const heading = $('map-surface-heading');
  const controls = $('map-query-controls');
  if (expanded) {
    stage.prepend(heading);
    $('map-surface-title').after(controls);
  } else {
    stage.before(heading);
    $('editor').before(controls);
  }
}
function setMapExpanded(expanded) {
  $('workspace').classList.toggle('map-expanded', expanded);
  syncMapSummary();
  const button = $('maximize-map');
  button.setAttribute('aria-pressed', String(expanded));
  button.title = expanded ? 'Restaurar tamanho do mapa' : 'Maximizar mapa';
  button.setAttribute('aria-label', button.title);
}
$('maximize-map').onclick = () => setMapExpanded(!$('workspace').classList.contains('map-expanded'));
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && drag?.kind !== 'transform' && !document.fullscreenElement) setMapExpanded(false);
});
$('fullscreen-map').onclick = async () => {
  const target = $('map-stage');
  try {
    if (document.fullscreenElement === target) await document.exitFullscreen();
    else await target.requestFullscreen();
  } catch { window.notify('Tela cheia indisponível neste navegador.', 'error'); }
};
document.addEventListener('fullscreenchange', () => {
  syncMapSummary();
  const mapLabel = document.fullscreenElement === $('map-stage') ? 'Sair da tela cheia' : 'Exibir em tela cheia';
  $('fullscreen-map').title = mapLabel;
  $('fullscreen-map').setAttribute('aria-label', mapLabel);
});
viewport.onpointerdown = event => {
  if (event.button !== 0 || (editing ? tool !== 'pan' : event.target.closest('g'))) return;
  stopMotion();
  pan = {id:event.pointerId, x:event.clientX, y:event.clientY, start:{...camera}};
  viewport.setPointerCapture(event.pointerId);
};
viewport.onpointermove = event => {
  if (pan?.id !== event.pointerId) return;
  camera.x = pan.start.x - (event.clientX - pan.x) / camera.scale;
  camera.y = pan.start.y - (event.clientY - pan.y) / camera.scale;
  resizeCanvas();
};
viewport.onlostpointercapture = () => { pan = null; };
$('canvas').onpointerdown = event => {
  if (!editing || tool !== 'select' || event.button !== 0 || event.target !== $('canvas')) return;
  const point = new DOMPoint(event.clientX, event.clientY).matrixTransform($('canvas').getScreenCTM().inverse());
  marquee = {pointer:event.pointerId, start:point, end:point,
    initial:new Set(event.shiftKey || event.ctrlKey || event.metaKey ? selection : [])};
  selectedLink = null;
  $('canvas').setPointerCapture(event.pointerId);
  selection.clear();
  for (const id of marquee.initial) selection.add(id);
  selected = selection.values().next().value || null;
  draw();
};
$('canvas').onpointermove = event => {
  if (marquee?.pointer === event.pointerId) {
    marquee.end = new DOMPoint(event.clientX, event.clientY).matrixTransform($('canvas').getScreenCTM().inverse());
    selection.clear();
    for (const id of marquee.initial) selection.add(id);
    for (const node of current.layout.nodes) {
      if (node.x >= Math.min(marquee.start.x, marquee.end.x) &&
          node.x <= Math.max(marquee.start.x, marquee.end.x) &&
          node.y >= Math.min(marquee.start.y, marquee.end.y) &&
          node.y <= Math.max(marquee.start.y, marquee.end.y)) selection.add(node.id);
    }
    selected = selection.values().next().value || null;
    draw();
    return;
  }
  if (!drag || drag.pointer !== event.pointerId) return;
  if (drag.kind === 'transform') {
    if (!drag.moved && Math.hypot(event.clientX - drag.x, event.clientY - drag.y) < 4) return;
    if (!drag.moved) checkpoint();
    drag.moved = true;
    const point = canvasPoint(event), node = drag.node, initial = drag.initial;
    if (drag.action === 'rotation') {
      const delta = Math.atan2(point.y - node.y, point.x - node.x)
        - Math.atan2(drag.start.y - node.y, drag.start.x - node.x);
      let angle = initial.rotation + delta * 180 / Math.PI;
      if (event.shiftKey) angle = Math.round(angle / 15) * 15;
      node.rotation = (angle % 360 + 360) % 360;
    } else if (drag.action === 'scale') {
      const distance = Math.hypot(drag.start.x - node.x, drag.start.y - node.y);
      node.scale = Math.max(.25, Math.min(3, Math.round(100 * initial.scale * Math.hypot(point.x - node.x, point.y - node.y) / Math.max(1, distance)) / 100));
    }
    draw(); return;
  }
  if (drag.kind === 'new-bend') {
    if (Math.hypot(event.clientX - drag.x, event.clientY - drag.y) < 4) return;
    checkpoint();
    selectedLink = drag.link; selected = null; selection.clear();
    const route = linkRoute(drag.link);
    drag.index = MapLinks.insertion(route, drag.start);
    drag.link.points ||= [];
    drag.link.points.splice(drag.index, 0, {x: drag.start.x, y: drag.start.y});
    drag.kind = 'bend'; drag.moved = true;
    lastConnectionClick = null;
    $('canvas').setPointerCapture(event.pointerId);
  }
  if (drag.kind === 'bend') {
    const point = canvasPoint(event);
    point.x += drag.dx || 0; point.y += drag.dy || 0;
    const snapped = MapLinks.snap(linkRoute(drag.link), drag.index + 1, point, camera.scale,
      current.layout.links.filter(link => link !== drag.link).map(linkRoute));
    drag.guides = snapped.guides;
    const previous = drag.link.points[drag.index];
    if (previous.x === snapped.position.x && previous.y === snapped.position.y) { draw(); return; }
    if (!drag.moved) { checkpoint(); $('canvas').setPointerCapture(event.pointerId); }
    drag.moved = true;
    drag.link.points[drag.index] = snapped.position;
    draw();
    return;
  }
  if (!drag.moved && Math.hypot(event.clientX - drag.x, event.clientY - drag.y) < 4) return;
  const point = new DOMPoint(event.clientX, event.clientY).matrixTransform($('canvas').getScreenCTM().inverse());
  if (!drag.moved) checkpoint();
  selected = drag.node.id;
  selectedLink = null;
  drag.moved = true;
  drag.guides = alignDraggedNode(drag.node, point.x + drag.dx, point.y + drag.dy,
    current.layout.nodes.filter(node => node === drag.node || !selection.has(node.id)), camera.scale);
  const origin = drag.positions.find(position => position.node === drag.node);
  const dx = drag.node.x - origin.x, dy = drag.node.y - origin.y;
  for (const position of drag.positions) {
    position.node.x = position.x + dx;
    position.node.y = position.y + dy;
  }
  draw();
};
$('canvas').onpointerup = event => {
  if (marquee?.pointer === event.pointerId) {
    marquee = null;
    draw();
    return;
  }
  if (drag?.pointer !== event.pointerId) return;
  if (drag.kind === 'transform') { drag = null; draw(); return; }
  if (drag.kind === 'new-bend') { drag = null; return; }
  if (drag.kind === 'bend' && drag.moved) suppressConnectionUntil = Date.now() + 400;
  if (drag.kind === 'bend') { const redraw = drag.moved || drag.guides?.length; drag = null; if (redraw) draw(); return; }
  const {node, moved, wasSingle} = drag;
  drag = null;
  if (!moved && wasSingle) openElement(node);
  draw();
};
function endDrag() {
  if (cancelTransform()) return;
  if (!drag && !marquee) return;
  drag = null;
  marquee = null;
  draw();
}
$('canvas').onlostpointercapture = endDrag;
$('canvas').onpointercancel = endDrag;

function setRefreshBusy(busy) {
  $('refresh-map').disabled = busy;
  $('refresh-map').setAttribute('aria-busy', String(busy));
}

$('refresh-map').onclick = () => {
  if (!current || editing || $('refresh-map').disabled) return;
  return refresh(++generation, true);
};

async function refresh(token, forceConnections = false) {
  if (!current || editing || token !== generation) return;
  clearTimeout(timer);
  setRefreshBusy(true);
  updateMapOverview();
  const mappedRefresh = forceConnections ? refreshMappedConnections(true) : Promise.resolve(true);
  const metricMode = mapView !== 'incidents';
  $('status').textContent = metricMode ? 'Consultando indicadores…' : 'Consultando incidentes…';
  try {
    const ids = [...new Set(current.layout.nodes.map(n => n.groupId).filter(Boolean))];
    const hostsIds = [...new Set(current.layout.nodes.map(n => n.hostId).filter(Boolean))];
    if (metricMode) {
      const [result, latency] = await Promise.all([
        api('/metrics', {groups:ids, hosts:hostsIds, minutes:Number($('map-period').value)}),
        mapView === 'latency' ? api('/latency-summaries', {groups:ids, hosts:hostsIds}) : null
      ]);
      if (token !== generation) return;
      metricStates = new Map(result.elements.map(e => [e.type + ':' + e.id, e]));
      metricRange = result;
      if (latency) latencySummaries = new Map(Object.entries(latency));
      summaryUpdatedAt = result.builtAt;
      summaryFailed = false;
      $('status').textContent = 'Indicadores do período até ' + new Date(result.to).toLocaleString('pt-BR') + ' · atualização automática a cada minuto' + (Date.now() - result.builtAt > 90000 ? ' · Dados desatualizados' : '');
    } else {
      const hosts = ids.length || hostsIds.length ? await api('/summaries', {groups:ids, hosts:hostsIds}) : [];
      if (token !== generation) return;
      summaryUpdatedAt = Date.now();
      summaryFailed = false;
      states = new Map();
      for (const host of hosts) states.set('host:' + host.id, [host]);
      for (const host of hosts) for (const group of host.groups) {
        if (!states.has(group.id)) states.set(group.id, []);
        states.get(group.id).push(host);
      }
      $('status').textContent = ids.length || hostsIds.length ? 'Incidentes atualizados às ' + new Date().toLocaleTimeString('pt-BR') : '';
    }
  } catch {
    if (token !== generation) return;
    summaryFailed = true;
    $('status').textContent = metricMode ? 'Consulta aos indicadores indisponível. Últimos dados preservados; nova tentativa em um minuto.' : 'Consulta ao Zabbix indisponível. Nova tentativa em um minuto.';
  } finally {
    const mappedOk = await mappedRefresh;
    if (token === generation && !mappedOk) $('status').textContent += ' · Consulta de interfaces ao Zabbix indisponível';
    if (token === generation) setRefreshBusy(false);
  }
  if (token !== generation) return;
  draw();
  popSummary.render();
  updateMapOverview();
  timer = setTimeout(() => refresh(token), 60000);
}

function pingStateFor(hostId) {
  const reading = pingStates.get(hostId);
  return reading?.measuredAt != null && Date.now() - reading.measuredAt <= 10000 ? reading.state : 'unknown';
}
function serverStatusFor(node) {
  const hosts = node.hostId ? [node.hostId] : node.groupId
    ? inventory.filter(host => host.groups?.some(group => group.id === node.groupId)).map(host => host.id) : [];
  const readings = hosts.map(id => ({state: pingStateFor(id), latency: pingStates.get(id)?.latencyMs}));
  const state = editing || !readings.length ? 'unknown'
    : readings.some(reading => reading.state === 'offline') ? 'offline'
    : readings.some(reading => reading.state === 'unknown') ? 'unknown'
    : readings.some(reading => Number.isFinite(reading.latency) && reading.latency >= 15) ? 'latency' : 'online';
  return state === 'latency' ? {color: '#fced4e', label: 'Latência elevada (ping)'} : MapSwitch.pingIndicator(state);
}
function updatePingIndicators() {
  for (const led of $('canvas').querySelectorAll('.server-status-led')) {
    const node = current.layout.nodes.find(node => node.id === led.dataset.serverNode);
    const status = serverStatusFor(node || {});
    led.setAttribute('fill', status.color); led.setAttribute('aria-label', status.label);
    led.querySelector('title').textContent = status.label;
  }
  for (const led of $('canvas').querySelectorAll('.switch-status-led')) {
    const status = MapSwitch.pingIndicator(editing ? 'unknown' : pingStateFor(led.dataset.pingHost));
    led.setAttribute('fill', status.color); led.setAttribute('aria-label', status.label);
    led.querySelector('title').textContent = status.label;
  }
}
async function refreshPingState(token) {
  if (!current || editing || token !== pingGeneration) return;
  clearTimeout(pingTimer);
  const equipment = current.layout.nodes.filter(n => ['switch', 'radio', 'sector-radio', 'tower-server', 'vm-server', 'battery'].includes(n.icon));
  const hosts = [...new Set(equipment.map(n => n.hostId).filter(Boolean))];
  const groups = [...new Set(equipment.filter(n => ['tower-server', 'vm-server', 'battery'].includes(n.icon)).map(n => n.groupId).filter(Boolean))];
  if (!hosts.length && !groups.length) return;
  try {
    const readings = await api('/ping-status', {hosts, groups});
    if (token !== pingGeneration || editing) return;
    pingStates = new Map(readings.map(reading => [reading.id, reading]));
  } catch {
    if (token !== pingGeneration || editing) return;
    pingStates.clear();
  }
  if (token !== pingGeneration || editing) return;
  updatePingIndicators();
  pingTimer = setTimeout(() => refreshPingState(token), 2000);
}

function openMap(map, edit = false, parents = []) {
  mapParents = mapAncestors(map?.id);
  const parent = maps.find(item => item.id === mapParents.at(-1));
  $('parent-map').hidden = edit || !parent;
  const parentLabel = parent ? 'Voltar ao mapa principal: ' + parent.layout.name : 'Voltar ao mapa principal';
  $('parent-map').title = parentLabel;
  $('parent-map').setAttribute('aria-label', parentLabel);
  if (edit && !canManageMaps) return;
  clearTimeout(pingTimer); pingGeneration++; pingStates.clear();
  popSummary.close();
  summaryUpdatedAt = 0;
  summaryFailed = false;
  clearTimeout(timer);
  generation++;
  setRefreshBusy(false);
  current = map ? JSON.parse(JSON.stringify(map)) : null;
  editing = edit;
  selection.clear();
  marquee = null;
  connectionOrigin = null;
  selected = drag = null;
  tool = 'select';
  selectedLink = null;
  past.length = future.length = 0;
  baseline = map ? JSON.stringify({...current.layout, camera:current.layout.camera ?? null}) : null;
  states.clear();
  metricStates.clear();
  metricRange = null;
  $('map-query-controls').hidden = edit;
  $('map-surface-heading').hidden = $('map-footer').hidden = edit;
  (map ? $('map-footer') : $('page-status')).append($('status'));
  $('editor').hidden = !edit;
  $('library').hidden = !!map;
  $('workspace').hidden = !map;
  $('edit').hidden = edit || !canManageMaps;
  $('editor-footer').hidden = $('editor-actions').hidden = !edit;
  $('map-description').textContent = edit ? 'Edite a topologia, adicione elementos, conecte e organize o mapa.' : 'Visualização do mapa de topologia da rede.';
  $('map-title').textContent = map?.layout.name || '';
  stopMotion();
  if ((!map || edit) && document.fullscreenElement === $('map-stage')) document.exitFullscreen();
  pan = null;
  $('workspace').classList.toggle('viewing', !!map && !edit);
  setMapExpanded(!!map && !edit);
  $('refresh-map').hidden = $('fullscreen-map').hidden = edit;
  $('maximize-map').hidden = !map;
  if (map) {
    viewport.scrollTo(0, 0);
    camera = map.layout.camera ? {...map.layout.camera} : {x:0, y:0, scale:1};
    syncZoom();
    resizeCanvas();
  }
  $('status').textContent = edit ? 'Editando topologia' : map ? 'Consultando incidentes…'
    : '';
  draw();
  updateMapOverview();
  if (map && (!edit || !map.layout.camera)) fitToView();
  refresh(generation);
  refreshPingState(pingGeneration);
  refreshMappedConnections();
}

let mapParents = [];
function mapAncestors(mapId) {
  const parents = [], seen = new Set([mapId]);
  let parent;
  while ((parent = maps.find(item => (item.layout.nodes || []).some(node => node.groupId && node.subMapId === mapId)))) {
    if (seen.has(parent.id)) break;
    seen.add(parent.id);
    parents.unshift(parent.id);
    mapId = parent.id;
  }
  return parents;
}
$('parent-map').onclick = event => {
  event.preventDefault();
  const parents = [...mapParents];
  const parentId = parents.pop();
  const parent = maps.find(map => map.id === parentId);
  if (!parent) { window.notify('Mapa principal indisponível.'); return; }
  openMap(parent, false, parents);
  setMapExpanded(true);
  fitToView();
};

const pageSize = 10;
let page = 0;
function renderList() {
  const query = $('map-search').value.trim().toLocaleLowerCase('pt-BR');
  const filtered = maps.filter(m => m.layout.name.toLocaleLowerCase('pt-BR').includes(query))
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  page = Math.min(page, Math.max(0, Math.ceil(filtered.length / pageSize) - 1));
  const start = page * pageSize;
  const rows = filtered.slice(start, start + pageSize);
  $('maps').replaceChildren();
  for (const map of rows) {
    const row = $('maps').insertRow();
    const layout = map.layout;
    const title = document.createElement('a');
    title.className = 'map-name-link';
    title.href = '#workspace';
    title.textContent = layout.name;
    title.setAttribute('aria-label', 'Visualizar mapa ' + layout.name);
    title.addEventListener('click', event => { event.preventDefault(); openMap(map); });
    row.insertCell().append(title);
    for (const value of [layout.nodes.length, layout.links.length,
      map.updatedAt ? new Date(map.updatedAt).toLocaleString('pt-BR', {dateStyle:'short', timeStyle:'short'}) : '—']) {
      row.insertCell().textContent = value;
    }
    row.insertCell().textContent = maps.some(parent => parent.id !== map.id &&
      parent.layout.nodes.some(node => node.groupId && node.subMapId === map.id)) ? 'Sub-mapa' : 'Principal';
  }
  $('maps-empty').hidden = rows.length > 0;
  $('map-count').textContent = `Exibindo ${filtered.length ? start + 1 : 0}–${start + rows.length} de ${filtered.length} mapas`;
  $('previous').hidden = $('next').hidden = false;
  $('previous').disabled = page === 0;
  $('next').disabled = start + rows.length >= filtered.length;
}
$('map-search').oninput = () => { page = 0; renderList(); };
$('previous').onclick = () => { page--; renderList(); };
$('next').onclick = () => { page++; renderList(); };

const createDialog = $('create-dialog');
const createForm = $('create-form');
const createFields = [...createForm.querySelectorAll('input')];
function fieldError(field, message) {
  $(field.id + '-error').textContent = message;
  field.setAttribute('aria-invalid', String(Boolean(message)));
}
$('create').onclick = () => {
  createForm.reset();
  createFields.forEach(field => fieldError(field, ''));
  $('create-error').textContent = '';
  createDialog.showModal();
  $('create-name').focus();
};
createForm.oninput = event => fieldError(event.target, '');
function cancelCreate() {
  createDialog.close();
  $('create').focus();
}
$('cancel-create').onclick = cancelCreate;
$('close-create-header').onclick = () => { if (!$('submit-create').disabled) cancelCreate(); };
createDialog.oncancel = event => {
  event.preventDefault();
  if (!$('submit-create').disabled) cancelCreate();
};
createForm.onsubmit = async event => {
  event.preventDefault();
  if ($('submit-create').disabled) return;
  const name = $('create-name').value.trim();
  fieldError($('create-name'), name ? '' : 'Informe o nome do mapa.');
  if (!name) { $('create-name').focus(); return; }
  $('create-error').textContent = '';
  createForm.setAttribute('aria-busy', 'true');
  createFields.forEach(field => { field.readOnly = true; });
  $('submit-create').disabled = $('cancel-create').disabled = true;
  $('submit-create-label').textContent = 'Criando…';
  try {
    const saved = await persist({layout:{name, nodes:[], links:[]}});
    createDialog.close();
    openMap(saved, true);
    $('add').focus();
  } catch (error) {
    $('create-error').textContent = error.message;
    $('create-name').focus();
  } finally {
    createFields.forEach(field => { field.readOnly = false; });
    $('submit-create').disabled = $('cancel-create').disabled = false;
    $('submit-create-label').textContent = 'Criar mapa';
    createForm.removeAttribute('aria-busy');
  }
};

const acknowledgedOptions = $('show-acknowledged').querySelectorAll('button');

function selectAcknowledged(showAcknowledged) {
  acknowledgedOptions.forEach(option => {
    option.setAttribute('aria-pressed', String((option.dataset.acknowledged === 'true') === showAcknowledged));
  });
}

acknowledgedOptions.forEach(option => {
  option.onclick = () => selectAcknowledged(option.dataset.acknowledged === 'true');
});

function showProperties() {
  const layout = current?.layout;
  $('map-error').textContent = '';
  $('name').value = layout?.name || '';
  $('minimum-severity').value = String(layout?.minimumSeverity ?? 0);
  selectAcknowledged(layout?.showAcknowledged ?? true);
  $('initial-view').querySelector('[value=saved]').hidden = !layout.camera;
  $('initial-view').value = followingFit ? 'fit' : 'current';
  $('map-dialog').showModal();
  $('name').focus();
}
$('properties').onclick = showProperties;
$('close-map').onclick = () => $('map-dialog').close();
$('close-map-header').onclick = () => $('map-dialog').close();
$('map-form').onsubmit = event => {
  event.preventDefault();
  if (!$('map-dialog').open) return;
  const name = $('name').value.trim();
  if (!name) { $('map-error').textContent = 'Informe o nome do mapa.'; return; }
  checkpoint();
  current.layout.name = name;
  current.layout.minimumSeverity = Number($('minimum-severity').value);
  current.layout.showAcknowledged =
    $('show-acknowledged').querySelector('[aria-pressed="true"]').dataset.acknowledged === 'true';
  const mode = $('initial-view').value;
  if (mode === 'fit') {
    current.layout.camera = null;
    fitToView();
  } else {
    stopMotion();
    if (mode === 'saved') camera = {...current.layout.camera};
    current.layout.camera = {...camera};
    syncZoom();
  }
  draw();
  resizeCanvas();
  $('map-dialog').close();
};
function layoutForSave() {
  return {...current.layout, camera:followingFit ? null : {...camera}};
}
function mayLeave() {
  return !editing || baseline === JSON.stringify(layoutForSave()) || confirm('Descartar alterações não salvas?');
}

$('edit').onclick = () => openMap(current, true);
$('cancel').onclick = () => { if (mayLeave()) openMap(maps.find(m => m.id === current.id)); };

function setTool(mode) {
  connectionOrigin = null;
  drag = marquee = null;
  tool = mode;
  selection.clear();
  selected = selectedLink = null;
  draw();
}
$('connect').onclick = () => setTool(tool === 'connect' ? 'select' : 'connect');
$('pan-tool').onclick = () => setTool('pan');
$('select-tool').onclick = () => setTool('select');
$('undo').onclick = () => restore(past, future);
$('redo').onclick = () => restore(future, past);
document.addEventListener('keydown', event => {
  if (!editing || $('workspace').inert || document.querySelector('dialog[open]') ||
      event.target.closest('input, textarea, select')) return;
  const key = event.key.toLowerCase();
  if ((event.ctrlKey || event.metaKey) && (key === 'z' || key === 'y')) {
    event.preventDefault();
    if (key === 'y' || event.shiftKey) restore(future, past); else restore(past, future);
  } else if (key === 'escape') { if (!cancelTransform()) setTool('select'); }
});

$('add').onclick = () => {
  pendingNode = {id:'element-' + Array.from(crypto.getRandomValues(new Uint32Array(4))).join('-'),
    name:'', x:Math.round(camera.x), y:Math.round(camera.y)};
  // Keep newly created elements individually reachable before they are arranged.
  const origin = {...pendingNode};
  for (let slot = 1; slot <= current.layout.nodes.length && current.layout.nodes.some(n =>
    Math.abs(n.x - pendingNode.x) < 18 && Math.abs(n.y - pendingNode.y) < 20); slot++) {
    pendingNode.x = origin.x + slot * 120;
    pendingNode.y = origin.y + slot * 48;
  }
  openElement(pendingNode);
};

function populateBinding(type, id) {
  const host = type === 'host';
  $('group-field').hidden = !type;
  const label = host ? 'host' : 'grupo';
  $('group-label').textContent = (host ? 'Selecionar host' : 'Selecionar grupo de hosts') + ' *';
  const field = $('group');
  const options = host ? inventory.map(h => new Option(h.name + (h.ip ? ' · ' + h.ip : ''), h.id))
    : groups.map(g => new Option(g.name, g.id));
  field.replaceChildren(new Option('Selecione um ' + label, ''), ...options);
  if (id && !options.some(o => o.value === id)) field.add(new Option('Indisponível (' + id + ')', id));
  field.value = id || '';
  window.availabilityGroupPicker.sync();
  const search = $('group-field').querySelector('input');
  search.placeholder = 'Pesquisar ' + label + '…';
  search.setAttribute('aria-label', search.placeholder);
  $('group-field').querySelector('[role=listbox]').setAttribute('aria-label',host ? 'Hosts' : 'Grupos');
  $('group-field').querySelector('p').textContent = 'Nenhum resultado.';
}
function canvasPoint(event) {
  return new DOMPoint(event.clientX, event.clientY).matrixTransform($('canvas').getScreenCTM().inverse());
}
function linkRoute(link) {
  const a = current.layout.nodes.find(node => node.id === link.from);
  const b = current.layout.nodes.find(node => node.id === link.to);
  return MapLinks.points(link, MapSwitch.endpoint(a, link.fromPort), MapSwitch.endpoint(b, link.toPort));
}
function connectionEndpointLabel(link, from) {
  const node = current.layout.nodes.find(n => n.id === (from ? link.from : link.to));
  if (!node) return 'Equipamento indisponível';
  const binding = node.hostId ? inventory.find(host => host.id === node.hostId)
    : node.groupId ? groups.find(group => group.id === node.groupId) : null;
  const type = node.hostId ? 'Host' : node.groupId ? 'Grupo de hosts' : 'Elemento';
  const id = node.hostId || node.groupId;
  const portId = from ? link.fromPort : link.toPort;
  const port = portId && MapSwitch.geometry(node).ports.find(p => p.id === portId);
  return `${type}: ${binding?.name || node.name}${id ? ' (ID: ' + id + ')' : ''}`
    + (port ? ' · ' + port.label : '');
}
function connectionHostId(node, index) {
  if (node?.hostId) return node.hostId;
  const id = $('connection-host-select-' + (index ? 'b' : 'a')).value;
  return node?.groupId && inventory.some(host => host.id === id && host.groups?.some(group => group.id === node.groupId)) ? id : null;
}
function prepareConnectionHosts(reset = false) {
  const draft = linkEndpointDraft();
  if (!draft) return;
  const existing = pendingLink.connection;
  ['from', 'to'].forEach((side, index) => {
    const suffix = index ? 'b' : 'a';
    const node = current.layout.nodes.find(n => n.id === draft[side]);
    const select = $('connection-host-select-' + suffix);
    $('connection-host-field-' + suffix).hidden = !node?.groupId;
    const candidates = inventory.filter(host => host.groups?.some(group => group.id === node?.groupId)
      && (node.linkInterfaces == null || node.linkInterfaces.some(entry => entry.hostId === host.id)));
    const savedHost = existing && (draft[side] === pendingLink.from ? existing.hostA : existing.hostB);
    const previous = reset ? savedHost : select.value;
    select.replaceChildren(new Option('Selecione o host do grupo', ''), ...candidates.map(host => new Option(host.name + (host.ip ? ' · ' + host.ip : ''), host.id)));
    select.value = candidates.some(host => host.id === previous) ? previous : '';
    connectionHostPickers[suffix]?.sync();
  });
}
const connectionHostPickers = {};
for (const suffix of ['a', 'b']) {
  const select = $('connection-host-select-' + suffix);
  connectionHostPickers[suffix] = window.setupGroupPicker?.(select, 'connection-host-label-' + suffix, 'Pesquisar host do grupo', {listLabel: 'Hosts do grupo', emptyText: 'Nenhum host encontrado.'});
  select.onchange = () => {
    ++interfaceLoad;
    $('connection-retry').removeAttribute('aria-busy');
    for (const side of ['a', 'b']) {
      $('connection-interface-' + side).replaceChildren();
      $('connection-interface-' + side).disabled = true;
    }
    $('connection-message').textContent = 'Clique em Carregar interfaces para listar as interfaces habilitadas dos hosts selecionados.';
    updateLinkEndpoints();
  };
}

let linkEndpointsReversed = false;
function linkEndpointDraft() {
  if (!linkEndpointsReversed || !pendingLink) return pendingLink;
  return {...pendingLink, from: pendingLink.to, to: pendingLink.from,
    fromPort: pendingLink.toPort, toPort: pendingLink.fromPort};
}
function swapLinkEndpoints() {
  const a = $('connection-interface-a'), b = $('connection-interface-b');
  if (!pendingLink || savingConnection || a.disabled || b.disabled) return;
  const optionsA = [...a.options], optionsB = [...b.options];
  const valueA = a.value, valueB = b.value;
  a.replaceChildren(...optionsB); b.replaceChildren(...optionsA);
  a.value = valueB; b.value = valueA;
  const hostA = $('connection-host-select-a'), hostB = $('connection-host-select-b');
  const hostOptionsA = [...hostA.options], hostOptionsB = [...hostB.options];
  const selectedHostA = hostA.value, selectedHostB = hostB.value;
  hostA.replaceChildren(...hostOptionsB); hostB.replaceChildren(...hostOptionsA);
  hostA.value = selectedHostB; hostB.value = selectedHostA;
  linkEndpointsReversed = !linkEndpointsReversed;
  prepareConnectionHosts();
  updateLinkEndpoints();
}
$('swap-connection-endpoints').onclick = swapLinkEndpoints;
function drawLinkEndpointArt(preview, node) {
  preview.replaceChildren();
  if (!node) return;
  const group = svg('g', {});
  preview.append(group);
  const color = node.color || colors[1];
  drawMapElement(group, node, color, false, {
    showLabel: false,
    connectedPorts: MapSwitch.connectedPorts(node, current.layout.links)
  });
  const {x, y, width, height} = group.getBBox();
  preview.setAttribute('viewBox', [x - 3, y - 3, width + 6, height + 6].join(' '));
}
function updateLinkEndpoints() {
  if (!pendingLink) return;
  const draft = linkEndpointDraft();
  ['from', 'to'].forEach((side, index) => {
    const node = current.layout.nodes.find(n => n.id === draft[side]);
    const portId = draft[side + 'Port'];
    const port = node && portId && MapSwitch.geometry(node).ports.find(p => p.id === portId);
    const selectedInterface = $('connection-interface-' + (index ? 'b' : 'a')).value;
    const mapped = $('link-category').value === 'mapped';
    const binding = node?.hostId ? inventory.find(host => host.id === node.hostId)
      : node?.groupId ? groups.find(group => group.id === node.groupId) : null;
    const name = binding?.name || node?.name || 'Equipamento indisponível';
    const id = node?.hostId || node?.groupId;
    const selectedHost = node?.groupId && inventory.find(host => host.id === connectionHostId(node, index));
    const identity = name + (id ? ' · ID ' + id : '') + (selectedHost ? ' · Host: ' + selectedHost.name : '');
    $('link-' + side).value = name;
    $('link-' + side).title = name;
    const interfaceName = (mapped ? selectedInterface : port?.name) || 'Interface não selecionada';
    $('link-' + side + '-interface').textContent = interfaceName;
    $('link-' + side + '-identity').textContent = id ? ' · ID ' + id : '';
    drawLinkEndpointArt($('link-' + side + '-art'), node);
    const speed = (!mapped || selectedInterface === port?.name) && port?.speed;
    $('link-' + side + '-capacity').hidden = !speed || /^(unknown|auto|0(?:bps)?)$/i.test(speed);
    $('link-' + side + '-capacity').textContent = speed || '';
    $('connection-host-' + (index ? 'b' : 'a')).textContent = identity;
    $('connection-host-' + (index ? 'b' : 'a')).title = identity;
  });
  drawLinkPreview();
}
function linkGeometry(type) {
  return {style: type === 'straight' ? 'straight' : 'corners',
    lineStyle: type === 'smooth' ? 'curved' : 'straight'};
}
function drawLinkPreview() {
  const appearance = $('link-dialog-style').value;
  const link = {...linkGeometry($('link-dialog-type').value), appearance,
    points: [{x: 180, y: 110}, {x: 620, y: 110}, {x: 620, y: 30}]};
  const y = link.style === 'corners' ? 30 : 70;
  const start = {x: 70, y}, end = {x: 730, y};
  const wireless = appearance.startsWith('dashed');
  const pulses = appearance.endsWith('-pulses');
  const line = svg('path', {d: MapLinks.path(link, start, end),
    stroke: appearance === 'solid-pulses' ? '#16A34A' : pulses ? '#22C55E' : 'var(--muted)',
    class: wireless ? 'link-preview-wireless' : '',
    fill: 'none', 'stroke-width': 2, 'vector-effect': 'non-scaling-stroke'});
  const preview = $('link-preview');
  preview.replaceChildren(line);
  if (pulses) {
    const layer = svg('g', {'aria-hidden': 'true'});
    preview.append(layer);
    layer.trafficContext = {link, wireless};
    MapTraffic.render(svg, layer, line, MapLinks.points(link, start, end), [], null, appearance !== 'solid-pulses' || $('traffic-animation').checked);
  }
}
function openLinkDialog(link) {
  if (!editing || $('link-dialog').open) return;
  pendingLink = link;
  linkEndpointsReversed = false;
  selectedLink = link; selected = null; selection.clear();
  $('link-from').value = connectionEndpointLabel(link, true);
  $('link-to').value = connectionEndpointLabel(link, false);
  $('link-dialog-type').value = link.lineStyle === 'curved' ? 'smooth' : link.style || 'straight';
  $('link-dialog-style').value = link.appearance || (
    (MapTraffic.isWireless(link, current.layout.nodes) ? 'dashed' : 'solid') +
    (link.mappedConnectionId ? '-pulses' : '')
  );
  draw();
  $('link-dialog').showModal();
  $('link-category').value = link.mappedConnectionId ? 'mapped' : 'simple';
  prepareConnectionHosts(true);
  $('connection-name').value = link.connection?.name || [link.from, link.to].map(id => current.layout.nodes.find(node => node.id === id)?.name || '').join(' ↔ ');
  loadConnectionInterfaces();
  $('delete-link').hidden = !current.layout.links.includes(link);
  $('link-category').focus();
}
$('delete-link').onclick = () => {
  if (!editing || !pendingLink || !current.layout.links.includes(pendingLink)) return;
  selectedLink = pendingLink; selected = null; selection.clear();
  deleteSelection();
  if (!current.layout.links.includes(pendingLink)) $('link-dialog').close();
};
$('link-dialog-type').onchange = $('link-dialog-style').onchange = drawLinkPreview;
$('close-link').onclick = () => $('link-dialog').close();
$('cancel-link').onclick = () => $('link-dialog').close();
$('link-dialog').onclose = () => { pendingLink = null; lastConnectionClick = null; };
$('link-form').onsubmit = async event => {
  event.preventDefault();
  if (!pendingLink || !current.layout.links.includes(pendingLink)) return;
  if (savingConnection) return;
  const target = pendingLink;
  let mappedConnectionId = target.mappedConnectionId;
  let connection = target.connection;
  if ($('link-category').value === 'mapped') {
    try {
      const [a, b] = connectionNodes(linkEndpointDraft());
      const hostA = connectionHostId(a, 0), hostB = connectionHostId(b, 1);
      if (!hostA || !hostB) throw new Error('Selecione um host em cada lado da conexão.');
      if (hostA === hostB) throw new Error('Selecione hosts diferentes para os lados A e B.');
      const name = $('connection-name').value.trim();
      const interfaceA = $('connection-interface-a').value, interfaceB = $('connection-interface-b').value;
      if (!name || !interfaceA || !interfaceB) throw new Error('Preencha o nome e as duas interfaces.');
      const existing = connectionDefinitions.find(c => c.id === target.mappedConnectionId);
      if (!existing || existing.name !== name || existing.interfaceA !== interfaceA || existing.interfaceB !== interfaceB || existing.hostA !== hostA || existing.hostB !== hostB) {
        savingConnection = true;
        $('save-link').disabled = true;
        $('save-link').setAttribute('aria-busy', 'true');
        $('save-link').setAttribute('class', 'system-button system-button-primary system-button-loading');
        $('save-link-label').textContent = 'Salvando';
        const saved = await connectionRequest('/api/maps/connections/validate', {name, hostA, hostB, interfaceA, interfaceB});
        if (pendingLink !== target || !current.layout.links.includes(target)) return;
        mappedConnectionId = saved.id; connection = saved;
      }
    } catch (error) { $('connection-message').textContent = error.message; return; }
    finally {
      savingConnection = false;
      $('save-link').disabled = false;
      $('save-link').removeAttribute('aria-busy');
      $('save-link').setAttribute('class', 'system-button system-button-primary');
      $('save-link-label').textContent = 'Salvar';
    }
  } else { mappedConnectionId = undefined; }
  const {style, lineStyle} = linkGeometry($('link-dialog-type').value);
  const appearance = $('link-dialog-style').value;
  const appearanceChanged = (target.style || 'straight') !== style ||
    (target.lineStyle || 'straight') !== lineStyle || target.appearance !== appearance;
  if (linkEndpointsReversed || mappedConnectionId !== target.mappedConnectionId || appearanceChanged) checkpoint();
  if (mappedConnectionId) { target.mappedConnectionId = mappedConnectionId; target.connection = connection; }
  else { delete target.mappedConnectionId; delete target.connection; }
  if (linkEndpointsReversed) {
    const draft = linkEndpointDraft();
    Object.assign(target, {from:draft.from, to:draft.to, fromPort:draft.fromPort, toPort:draft.toPort});
    if (target.points) target.points = [...target.points].reverse();
  }
  if (appearanceChanged) {
    Object.assign(target, {style, lineStyle, appearance});
    target.points ||= [];
  }
  $('link-dialog').close(); draw();
};
function switchDraft() {
  const ports = MapSwitch.portsForSlots(configuredPorts, configuredSlots);
  return ['radio', 'sector-radio'].includes($('element-icon').value) && ports?.length > 4 ? null : ports;
}
function renderSwitchSlots() {
  const panel = $('switch-slots');
  panel.replaceChildren();
  panel.setAttribute('aria-busy', String(loadingInterfaces));
  if (loadingInterfaces) {
    const row = document.createElement('tr'), cell = document.createElement('td');
    cell.colSpan = 6; cell.className = 'element-slot-empty';
    const spinner = document.createElement('span');
    spinner.className = 'element-interface-spinner'; spinner.setAttribute('aria-hidden', 'true');
    const label = document.createElement('span');
    label.className = 'sr-only'; label.textContent = 'Carregando interfaces';
    cell.append(spinner, label); row.append(cell); panel.append(row);
    return;
  }
  if (!configuredSlots.length) {
    const row = document.createElement('tr'), cell = document.createElement('td');
    cell.colSpan = 6; cell.className = 'element-slot-empty';
    cell.textContent = 'Nenhum slot configurado. Adicione um slot para começar.';
    row.append(cell); panel.append(row);
  }
  configuredSlots.forEach((slot, index) => {
    const row = document.createElement('tr');
    const heading = document.createElement('th'); heading.scope = 'row';
    heading.textContent = index + 1; row.append(heading);
    const field = (text, control) => {
      const cell = document.createElement('td');
      control.setAttribute('aria-label', text + ' do slot ' + (index + 1));
      cell.append(control); row.append(cell);
    };
    const name = document.createElement('input');
    name.type = 'text'; name.value = slot.name; name.maxLength = 80; name.required = true;
    name.readOnly = !!slot.interfaceNames;
    name.placeholder = 'Ex.: ether, 1/1/';
    name.oninput = () => { slot.name = name.value; drawPreview(); };
    field('Nome', name);
    const connector = document.createElement('select');
    for (const [value, option] of Object.entries(MapSwitch.connectors)) connector.add(new Option(option.label, value));
    connector.value = slot.connector;
    for (const option of connector.options) if (option.value === 'wireless') option.disabled = configuredSlots.some(other => other !== slot && other.connector === 'wireless');
    connector.onchange = () => { slot.connector = connector.value; renderSwitchSlots(); drawPreview(); };
    field('Conector', connector);
    const capacity = document.createElement('select');
    capacity.add(new Option('Não identificada', 'unknown'));
    for (const [value, label] of Object.entries(MapSwitch.portCapacities)) capacity.add(new Option(label, value));
    capacity.value = slot.capacity;
    capacity.onchange = () => { slot.capacity = capacity.value; drawPreview(); };
    field('Capacidade', capacity);
    const count = document.createElement('input');
    count.type = 'number'; count.min = 1; count.max = slot.connector === 'wireless' ? 1 : 96; count.required = true; count.value = slot.count;
    count.oninput = () => {
      slot.count = Number(count.value);
      if (Array.isArray(slot.interfaceNames) && Number.isInteger(slot.count) && slot.count >= 0 && slot.count <= 96) {
        const names = slot.interfaceNames.slice(0, slot.count);
        const used = new Set(names);
        let suffix = 1;
        while (names.length < slot.count) {
          const candidate = slot.name.trim() + suffix++;
          if (used.has(candidate)) continue;
          names.push(candidate);
          used.add(candidate);
        }
        slot.interfaceNames = names;
      }
      drawPreview();
    };
    field('Portas', count);
    const remove = document.createElement('button');
    remove.type = 'button'; remove.className = 'system-button system-button-icon-only';
    remove.title = 'Remover slot'; remove.setAttribute('aria-label', 'Remover slot ' + (index + 1));
    const icon = svg('svg', {class: 'system-button-icon', viewBox: '0 0 24 24', 'aria-hidden': 'true'});
    icon.append(svg('use', {href: '/icons.svg#trash-2'})); remove.append(icon);
    remove.onclick = () => {
      configuredSlots.splice(index, 1); renderSwitchSlots(); drawPreview();
      const inputs = $('switch-slots').querySelectorAll('input[type=text]');
      (inputs[Math.min(index, inputs.length - 1)] || $('add-switch-slot')).focus();
    };
    const actions = document.createElement('td'); actions.append(remove); row.append(actions); panel.append(row);
  });
}
$('load-switch-interfaces').onclick = async () => {
  const hostId = $('type').value === 'host' ? $('group').value : null;
  if (!hostId || loadingInterfaces) return;
  const generation = interfaceLoadGeneration;
  const previous = switchDraft() || configuredPorts;
  loadingInterfaces = true;
  $('load-switch-interfaces').disabled = true;
  $('interface-load-status').textContent = '';
  $('interface-load-status').setAttribute('role', 'status');
  renderSwitchSlots();
  let failureMessage = 'Não foi possível acessar a aplicação para consultar as interfaces. Verifique a conexão com o servidor.';
  try {
    const response = await fetch('/api/maps/connections/discover/' + encodeURIComponent(hostId), {cache: 'no-store'});
    failureMessage = `A consulta de interfaces retornou uma resposta inválida (HTTP ${response.status}). Verifique a versão da aplicação e o servidor de acesso.`;
    if (response.redirected) {
      failureMessage = 'A consulta foi redirecionada. Entre novamente na aplicação e tente carregar as interfaces.';
      throw new Error();
    }
    const data = await response.json();
    if (!response.ok) {
      if (typeof data?.message === 'string' && data.message.trim())
        failureMessage = data.message;
      else if (response.status === 400 || response.status === 409)
        failureMessage = 'Não foi possível carregar as interfaces. Confira os itens de interface do host no Zabbix e tente novamente.';
      throw new Error();
    }
    if (generation !== interfaceLoadGeneration || !$('element-dialog').open
        || $('type').value !== 'host' || $('group').value !== hostId) return;
    if (!Array.isArray(data)) throw new Error();
    failureMessage = 'As interfaces foram recebidas, mas não foi possível montar as portas no navegador. Atualize a página para carregar a versão atual do mapa.';
    const imported = MapSwitch.importInterfaces(data, previous);
    if (!imported.ports.length) {
      failureMessage = 'Nenhuma interface com item de estado encontrada no Zabbix. A configuração atual foi preservada.';
      throw new Error();
    }
    if (['radio', 'sector-radio'].includes($('element-icon').value) && imported.ports.length > 4) {
      failureMessage = 'O host possui mais de 4 interfaces. Configure manualmente até 4 interfaces para este rádio. A configuração atual foi preservada.';
      throw new Error();
    }
    configuredSlots = imported.slots;
    configuredPorts = imported.ports;
    renderSwitchSlots(); drawPreview();
    $('interface-load-status').textContent = imported.ports.length + ' interfaces carregadas. Salve o elemento para aplicar.'
      + (imported.ports.some(port => port.capacity === 'unknown') ? ' Revise as capacidades não identificadas.' : '')
      + (imported.ports.filter(port => port.connector === 'wireless').length > 1 ? ' O Zabbix lista mais de uma interface Wireless. Remova os slots excedentes e mantenha apenas a interface do enlace antes de salvar; nenhuma interface foi escolhida automaticamente.' : '')
      + (imported.unidentifiedConnectors?.length ? ' Conectores não identificados pelo Zabbix: ' + imported.unidentifiedConnectors.join(', ') + '. Confira os conectores; os anteriores foram preservados quando disponíveis, e as novas portas usam Elétrico provisoriamente.' : '');
  } catch (error) {
    if (generation === interfaceLoadGeneration && $('type').value === 'host' && $('group').value === hostId) {
      $('interface-load-status').setAttribute('role', 'alert');
      $('interface-load-status').textContent = failureMessage;
    }
  } finally {
    if (generation === interfaceLoadGeneration) { loadingInterfaces = false; renderSwitchSlots(); drawPreview(); }
  }
};
$('add-switch-slot').onclick = () => {
  if (['radio', 'sector-radio'].includes($('element-icon').value) && configuredSlots.reduce((sum, slot) => sum + slot.count, 0) >= 4) return;
  configuredSlots.push({id: MapSwitch.newId(), name: '', connector: 'electrical', capacity: '1g', count: 1});
  renderSwitchSlots(); drawPreview();
  $('switch-slots').lastElementChild.querySelector('input').focus();
};
function connectEndpoint(node, portId) {
  if (!editing) return;
  if (tool !== 'connect') { setTool('connect'); }
  const port = node.ports?.find(port => port.id === portId);
  const occupied = portId && port?.connector !== 'pon' && current.layout.links.some(link =>
    (link.from === node.id && link.fromPort === portId) || (link.to === node.id && link.toPort === portId));
  if (occupied) { $('hint').textContent = 'Esta porta já possui uma conexão.'; return; }
  if (!connectionOrigin) {
    connectionOrigin = {nodeId: node.id, portId};
    selected = node.id;
    draw();
    return;
  }
  const origin = connectionOrigin;
  if (origin.nodeId === node.id) return;
  const duplicate = current.layout.links.some(link =>
    (link.from === origin.nodeId && link.to === node.id && (link.fromPort || null) === origin.portId && (link.toPort || null) === portId) ||
    (link.to === origin.nodeId && link.from === node.id && (link.toPort || null) === origin.portId && (link.fromPort || null) === portId));
  if (!duplicate) {
    checkpoint();
    current.layout.links.push({from: origin.nodeId, to: node.id, fromPort: origin.portId, toPort: portId});
  }
  setTool('select');
  draw();
}
function syncInterfaceControls() {
  const isGroup = $('type').value === 'group';
  $('element-interfaces').hidden = isGroup;
  $('interfaces-enabled').disabled = isGroup;
  const enabled = !isGroup && $('interfaces-enabled').checked;
  $('element-slot-actions').hidden = !enabled;
  $('switch-config').hidden = !enabled;
  $('switch-config').disabled = !enabled;
  return enabled;
}
function drawPreview() {
  const preview = $('node-preview');
  const group = svg('g', {});
  preview.replaceChildren(group);
  const name = $('element-name').value.trim() || 'Nome do elemento';
  const icon = $('element-icon').value;
  $('label-position').disabled = icon === 'pop';
  if (icon === 'pop') $('label-position').value = 'BELOW';
  const isRadio = ['radio', 'sector-radio'].includes(icon);
  const isSwitch = ['switch', 'radio', 'sector-radio'].includes(icon);
  $('element-color-note').hidden = isSwitch;
  $('switch-color-field').hidden = !isSwitch || isRadio;
  $('reset-switch-color').disabled = !configuredSwitchColor;
  const interfacesEnabled = syncInterfaceControls();
  $('load-switch-interfaces').hidden = !isSwitch || $('type').value !== 'host';
  $('load-switch-interfaces').disabled = loadingInterfaces || !$('group').value;
  $('add-switch-slot').disabled = isRadio && configuredSlots.reduce((sum, slot) => sum + slot.count, 0) >= 4;
  const ports = switchDraft();
  $('port-error').textContent = interfacesEnabled && !ports ? (isRadio ? 'O rádio permite no máximo 4 interfaces. Remova ou ajuste os slots excedentes.' : 'Preencha o nome e a capacidade de cada slot e informe de 1 a 96 portas por slot.') : '';
  const binding = {hostId: $('type').value === 'host' ? $('group').value : null,
    groupId: $('type').value === 'group' ? $('group').value : null};
  const monitored = !!(binding.hostId || binding.groupId);
  const color = monitored ? metricSummary(binding)?.color || colors[groupLevel(nodeHosts(binding)) + 2] : colors[0];
  if (isSwitch) MapSwitch.draw(svg, group, {...transformState(current.layout.nodes.find(n => n.id === configuredNodeId) || {}), icon, name, labelPosition: $('label-position').value,
    ports: ports || [], interfacesEnabled, switchColor: configuredSwitchColor}, color, false,
    {connectedPorts: MapSwitch.connectedPorts({id: configuredNodeId}, current.layout.links)});
  else if (icon === 'pop') drawPop(group, {...(current.layout.nodes.find(n => n.id === configuredNodeId) || {}), icon, name}, 'normal');
  else (['telecom-site', 'cloud', 'tower-server', 'vm-server', 'battery'].includes(icon) ? drawTelecomSite : drawNode)(group, name, $('label-position').value, color, false, {...(current.layout.nodes.find(n => n.id === configuredNodeId) || {}), icon});
  const box = group.getBBox();
  const w = Math.max(140, box.width + 20), h = Math.max(66, box.height + 12);
  preview.setAttribute('viewBox', [box.x + (box.width-w)/2, box.y + (box.height-h)/2, w, h].join(' '));
  preview.setAttribute('aria-label', (MapSwitch.serverImages?.[icon]?.label || (icon === 'pop' ? 'POP Status Node' : icon === 'cloud' ? 'Nuvem' : icon === 'telecom-site' ? 'Estação de telecomunicações' : isRadio ? 'Rádio' : isSwitch ? 'Switch' : 'Círculo')) + ' · ' + name);
}

function openElement(node, type = node.hostId ? 'host' : node.groupId ? 'group' : '') {
  interfaceLoadGeneration++; loadingInterfaces = false;
  $('interface-load-status').textContent = '';
  configuredPorts = node.ports || [];
  configuredNodeId = node.id;
  configuredHostId = node.hostId || null;
  configuredSwitchColor = node.switchColor || null;
  $('switch-color').value = configuredSwitchColor || '#e0e3e8';
  $('element-icon').value = node.icon || 'circle';
  configuredSlots = MapSwitch.slotsFor(node).map(MapSwitch.normalizeSlot);
  $('interfaces-enabled').checked = node.interfacesEnabled ?? !!(node.ports?.length || node.slots?.length);
  renderSwitchSlots();
  $('element-name').value = node.name;
  $('element-observation').value = node.observation || '';
  $('label-position').value = node.labelPosition || 'BELOW';
  $('type').value = type;
  syncElementIcons();
  $('remove').hidden = !!pendingNode;
  populateBinding(type,node.hostId || node.groupId);
  syncSubMap(node.subMapId || '');
  if (typeof groupLinkEditor !== 'undefined' && groupLinkEditor) {
    groupLinkEditor.open(node, current.layout.links);
    groupLinkEditor.sync($('group').value, type === 'group');
  }
  $('element-dialog').removeAttribute('style');
  window.elementIconPicker?.sync();
  $('element-dialog').showModal();
  $('element-form').querySelector('.form-dialog-content').scrollTop = 0;
  drawPreview();
  $('element-name').focus();
}
function syncSubMap(selectedId = $('sub-map').value) {
  $('sub-map-field').hidden = $('type').value !== 'group';
  $('sub-map').replaceChildren(new Option('Nenhum submapa', ''), ...maps
    .filter(map => {
      if (map.id === current.id || mapAncestors(current.id).includes(map.id)) return false;
      return !maps.some(parent => (parent.id === current.id ? current.layout.nodes : parent.layout.nodes || [])
        .some(node => (parent.id !== current.id || node.id !== configuredNodeId)
          && node.groupId && node.subMapId === map.id));
    })
    .map(map => new Option(map.layout.name, map.id)));
  if (selectedId && !maps.some(map => map.id === selectedId)) {
    $('sub-map').add(new Option('Indisponível (' + selectedId + ')', selectedId));
  }
  $('sub-map').value = selectedId || '';
}
function syncElementIcons() {
  const binding = $('type').value || 'free';
  const select = $('element-icon');
  for (const option of select.options) {
    const allowed = (option.dataset.bindings || 'group host free').split(' ').includes(binding);
    option.disabled = !allowed; option.hidden = !allowed;
  }
  const selectedOption = [...select.options].find(option => option.value === select.value);
  if (!selectedOption || selectedOption.disabled) select.value = [...select.options].find(option => !option.disabled)?.value || '';
  window.elementIconPicker?.sync();
}
window.elementIconPicker = window.setupSinglePicker($('element-icon'));
$('element-icon').onchange = drawPreview;
$('switch-color').oninput = () => { configuredSwitchColor = $('switch-color').value; drawPreview(); };
$('reset-switch-color').onclick = () => {
  configuredSwitchColor = null; $('switch-color').value = '#e0e3e8'; drawPreview();
};
$('interfaces-enabled').onchange = drawPreview;
$('element-name').oninput = $('label-position').onchange = drawPreview;
function resetInterfacesForHostChange() {
  const hostId = $('type').value === 'host' ? $('group').value || null : null;
  if (hostId === configuredHostId) return;
  configuredHostId = hostId;
  interfaceLoadGeneration++;
  loadingInterfaces = false;
  configuredPorts = [];
  configuredSlots = [];
  $('interfaces-enabled').checked = false;
  $('interface-load-status').textContent = '';
  renderSwitchSlots();
}
$('type').onchange = () => {
  syncElementIcons();
  populateBinding($('type').value);
  resetInterfacesForHostChange();
  if (typeof groupLinkEditor !== 'undefined') groupLinkEditor?.sync($('group').value, $('type').value === 'group');
  syncSubMap();
  drawPreview();
};
$('group').addEventListener('change', () => {
  resetInterfacesForHostChange();
  if (typeof groupLinkEditor !== 'undefined') groupLinkEditor?.sync($('group').value, $('type').value === 'group');
  if (!$('element-name').value.trim()) $('element-name').value = ($('type').value === 'host' ? inventory : groups)
    .find(item => item.id === $('group').value)?.name || '';
  drawPreview();
});
$('close-element').onclick = $('cancel-element').onclick = () => $('element-dialog').close();
$('element-dialog').onclose = () => { pendingNode = null; };
$('element-form').onsubmit = event => {
  event.preventDefault();
  const name = $('element-name').value.trim(), type = $('type').value, binding = $('group').value;
  if (!name) { $('element-name').focus(); return; }
  if (type && !binding) { $('group-field').querySelector('.group-picker-trigger').click(); return; }
  const node = pendingNode || current.layout.nodes.find(n => n.id === selected);
  const linkInterfaces = type === 'group' && typeof groupLinkEditor !== 'undefined' && groupLinkEditor ? groupLinkEditor.value() : node.linkInterfaces;
  if (type === 'group' && typeof groupLinkEditor !== 'undefined' && groupLinkEditor?.validate(binding)) return;
  if (type === 'group' && linkInterfaces && current.layout.links.some(link => {
    const side = link.from === node.id ? 'A' : link.to === node.id ? 'B' : null;
    return side && link.connection && !linkInterfaces.some(entry => entry.hostId === link.connection['host' + side] && entry.name === link.connection['interface' + side]);
  })) { $('port-error').textContent = 'Existe um enlace usando uma interface removida da lista. Reconfigure ou remova o enlace antes de desabilitar a interface.'; return; }
  const icon = $('element-icon').value;
  const interfacesEnabled = $('interfaces-enabled').checked;
  const ports = switchDraft();
  if (!ports) {
    drawPreview();
    $('port-error').textContent = (['radio', 'sector-radio'].includes(icon) ? 'O rádio permite no máximo 4 interfaces, sendo apenas uma Wireless. Revise os nomes, capacidades e quantidades dos slots.' : 'Existem slots inválidos ou mais de uma interface Wireless. Habilite as interfaces e preencha nome, capacidade e quantidade de 1 a 96 portas.');
    $('port-error').scrollIntoView({block: 'nearest'});
    return;
  }
  const ids = new Set(ports.map(port => port.id));
  const connectionsPerPort = new Map();
  for (const link of current.layout.links) {
    const portId = link.from === node.id ? link.fromPort : link.to === node.id ? link.toPort : null;
    if (portId) connectionsPerPort.set(portId, (connectionsPerPort.get(portId) || 0) + 1);
  }
  const sharedPort = ports.find(port => port.connector !== 'pon' && connectionsPerPort.get(port.id) > 1);
  if (sharedPort) {
    $('port-error').textContent = sharedPort.name + ' tem vários enlaces. Remova os excedentes antes de mudar o conector.';
    $('port-error').scrollIntoView({block: 'nearest'});
    return;
  }
  const affected = current.layout.links.filter(link => (link.from === node.id && link.fromPort && !ids.has(link.fromPort))
    || (link.to === node.id && link.toPort && !ids.has(link.toPort)));
  const affectedNames = affected.map(link => {
    const other = current.layout.nodes.find(n => n.id === (link.from === node.id ? link.to : link.from));
    const portId = link.from === node.id ? link.fromPort : link.toPort;
    const port = MapSwitch.geometry(node).ports.find(p => p.id === portId);
    return (port?.label || portId) + ' → ' + (other?.name || 'equipamento');
  });
  if (affected.length && !confirm('A alteração remove as conexões abaixo:\n' + affectedNames.join('\n') + '\nContinuar?')) return;
  checkpoint();
  current.layout.links = current.layout.links.filter(link => !affected.includes(link));
  Object.assign(node, {observation: $('element-observation').value.trim() || null, linkInterfaces: type === 'group' ? linkInterfaces : null, icon, switchColor: configuredSwitchColor, interfacesEnabled, ports, slots: configuredSlots.map(slot => ({...slot, name: slot.name.trim()})), name, labelPosition:$('label-position').value, hostId:type === 'host' ? binding : null,
    groupId:type === 'group' ? binding : null, subMapId:type === 'group' ? $('sub-map').value || null : null, color:node.color || null});
  ensureElementOrigin(node);
  if (pendingNode) current.layout.nodes.push(node);
  pendingNode = null;
  selected = node.id;
  selection.clear();
  selection.add(node.id);
  selectedLink = null;
  tool = 'select';
  $('element-dialog').close();
  draw();
  clearTimeout(timer);
  refresh(++generation);
};
function deleteSelection() {
  if ((!selection.size && !selected && !selectedLink) || !confirm(selectedLink ? 'Excluir a conexão?' : selection.size > 1 ? 'Excluir os elementos selecionados e suas conexões?' : 'Excluir o elemento e suas conexões?')) return;
  checkpoint();
  if (selectedLink) current.layout.links = current.layout.links.filter(l => l !== selectedLink);
  else {
    const ids = new Set(selection.size ? selection : [selected]);
    current.layout.nodes = current.layout.nodes.filter(n => !ids.has(n.id));
    current.layout.links = current.layout.links.filter(l => !ids.has(l.from) && !ids.has(l.to));
  }
  selection.clear();
  selected = selectedLink = null;
  if ($('element-dialog').open) $('element-dialog').close();
  draw();
}
$('delete-item').onclick = $('remove').onclick = deleteSelection;

const handle = $('element-title');
handle.onpointerdown = event => {
  if (event.button !== 0) return;
  const dialog = $('element-dialog');
  const rect = dialog.getBoundingClientRect();
  const dx = event.clientX - rect.left, dy = event.clientY - rect.top;
  handle.setPointerCapture(event.pointerId);
  handle.onpointermove = move => {
    dialog.style.margin = '0';
    dialog.style.left = Math.max(0, Math.min(innerWidth - rect.width, move.clientX - dx)) + 'px';
    dialog.style.top = Math.max(0, Math.min(innerHeight - rect.height, move.clientY - dy)) + 'px';
  };
  handle.onlostpointercapture = () => { handle.onpointermove = null; };
};

async function persist(map) {
  const saved = await api('', map);
  maps = maps.filter(m => m.id !== saved.id).concat(saved);
  renderList();
  return saved;
}
$('save').onclick = async () => {
  if ($('workspace').inert) return;
  $('workspace').inert = true;
  $('save').disabled = true;
  $('save').setAttribute('aria-busy', 'true');
  $('save-label').textContent = 'Salvando…';
  try { openMap(await persist({...current, layout:layoutForSave()})); window.notify('Mapa salvo.'); }
  catch (error) { $('status').textContent = error.message; }
  finally {
    $('workspace').inert = false;
    $('save').disabled = false;
    $('save').removeAttribute('aria-busy');
    $('save-label').textContent = 'Salvar mapa';
  }
};

(async () => {
  try {
    [maps, groups, inventory] = await Promise.all([api(''), api('/groups'), api('/hosts')]);
    renderList();
    $('create').disabled = !canManageMaps;
    openMap(null);
  } catch { $('status').textContent = 'Falha ao carregar os mapas. Recarregue a página para tentar novamente.'; }
})();


let connectionDefinitions = [], connectionReadings = new Map(), connectionFailures = new Set();
// Keep the last visible snapshot until each connection receives its first resumed result.
const connectionPausedAt = new Map();
function connectionDisplayTime(id) { return connectionPausedAt.get(id) ?? Date.now(); }
async function connectionRequest(url, body, headers = {}) {
  const options = body === undefined ? {} : {method:'POST', headers:{'Content-Type':'application/json', ...headers}, body:JSON.stringify(body)};
  if (typeof AbortSignal !== 'undefined' && AbortSignal.timeout) options.signal = AbortSignal.timeout(90000);
  const response = await fetch(url, options);
  if (!response.ok) { const error = await response.json().catch(() => ({})); throw new Error(error.message || error.detail || 'Não foi possível consultar a conexão. Verifique a configuração da API.'); }
  return response.json();
}
function connectionNodes(link) { return [link.from, link.to].map(id => current.layout.nodes.find(n => n.id === id)); }
let interfaceLoad = 0;
async function loadConnectionInterfaces() {
  const sequence = ++interfaceLoad, link = pendingLink;
  const draft = linkEndpointDraft();
  const mapped = $('link-category').value === 'mapped';
  $('link-mapping').hidden = !mapped;
  updateLinkEndpoints();
  if (!mapped || !link) {
    $('connection-retry').removeAttribute('aria-busy');
    return;
  }
  $('connection-retry').setAttribute('aria-busy', 'true');
  $('swap-connection-endpoints').disabled = true;
  const controls = [$('connection-interface-a'), $('connection-interface-b')];
  controls.forEach(c => { c.replaceChildren(); c.disabled = true; });
  $('connection-message').textContent = '';
  try {
    const nodes = connectionNodes(draft);
    prepareConnectionHosts();
    const hostIds = nodes.map(connectionHostId);
    if (hostIds.some(id => !id)) throw new Error('Selecione o host de cada grupo e clique em Carregar interfaces.');
    if (hostIds[0] === hostIds[1]) throw new Error('Selecione hosts diferentes para os lados A e B.');
    connectionDefinitions = current.layout.links.map(link => link.connection).filter(Boolean);
    const existing = connectionDefinitions.find(c => c.id === link.mappedConnectionId);
    const interfaces = nodes.map((node, index) => {
      if (node.groupId && node.linkInterfaces != null) return node.linkInterfaces.filter(entry => entry.hostId === hostIds[index]);
      const element = node.hostId === hostIds[index] ? node
        : current.layout.nodes.find(candidate => candidate.hostId === hostIds[index]);
      return element?.interfacesEnabled === false ? [] : [...new Map((element?.ports || [])
        .filter(port => port.name).map(port => [port.name, port])).values()]
        .sort((a, b) => a.name.localeCompare(b.name, 'en', {numeric: true, sensitivity: 'base'}));
    });
    if (sequence !== interfaceLoad || pendingLink !== link) return;
    interfaces.forEach((rows, i) => {
      controls[i].append(new Option('Selecione a interface', ''));
      rows.forEach(row => controls[i].append(new Option(row.comment?.trim() ? row.name + ' - ' + row.comment.trim() : row.name, row.name)));
      const selected = (existing && (hostIds[i] === existing.hostA ? existing.interfaceA : hostIds[i] === existing.hostB ? existing.interfaceB : '')) || '';
      controls[i].value = rows.some(row => row.name === selected) ? selected : '';
      controls[i].disabled = false;
    });
    updateLinkEndpoints();
    $('connection-message').textContent = interfaces.some(rows => !rows.length) ? 'Cadastre as interfaces no elemento do mapa: portas do host ou interfaces habilitadas para enlaces do grupo.' : '';
  } catch (error) { if (sequence === interfaceLoad) $('connection-message').textContent = error.message; }
  finally { if (sequence === interfaceLoad) {
    $('connection-retry').removeAttribute('aria-busy');
    $('swap-connection-endpoints').disabled = controls.some(control => control.disabled);
  } }
}
$('link-category').onchange = loadConnectionInterfaces;
$('connection-interface-a').onchange = $('connection-interface-b').onchange = () => updateLinkEndpoints();
$('connection-retry').onclick = loadConnectionInterfaces;
function connectionRate(value) { return MapTraffic.rate(value); }
function updateTrafficLayer(layer) {
  const {line, link, route, nodes} = layer.trafficContext;
  const definition = connectionDefinitions.find(c => c.id === link.mappedConnectionId);
  const from = nodes.find(n => n.id === link.from);
  const reference = connectionReadings.get(link.mappedConnectionId)?.hostA || definition?.hostA;
  MapTraffic.render(svg, layer, line, route, nodes, connectionReadings.get(link.mappedConnectionId),
    $('traffic-animation').checked, Boolean(!link.appearance && reference && reference !== (from?.hostId || definition?.hostA)),
    connectionDisplayTime(link.mappedConnectionId));
}
try { $('traffic-animation').checked = localStorage.getItem('map-traffic-animation') !== 'false'; } catch {}
$('traffic-animation').onchange = () => {
  try { localStorage.setItem('map-traffic-animation', String($('traffic-animation').checked)); } catch {}
  document.querySelectorAll('.map-traffic').forEach(updateTrafficLayer);
};
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    const now = Date.now();
    connectionReadings.forEach((_, id) => {
      if (!connectionPausedAt.has(id)) connectionPausedAt.set(id, now);
    });
  } else {
    refreshMappedConnections();
  }
});
function mappedConnectionTitle(id) {
  const reading = connectionReadings.get(id);
  if (!reading) return connectionFailures.has(id) ? 'Conexão mapeada · Consulta indisponível' : 'Conexão mapeada · Aguardando coleta';
  const definition = connectionDefinitions.find(c => c.id === id);
  const now = connectionDisplayTime(id);
  const data = MapTraffic.state(reading, now);
  const utilization = value => value == null || !data.capacity ? 'Sem dados' : (value / data.capacity * 100).toLocaleString('pt-BR', {maximumFractionDigits: 2}) + '%';
  return [reading.a, reading.b].map((e, i) => {
    const hostId = i ? reading.hostB || definition?.hostB : reading.hostA || definition?.hostA;
    const host = inventory.find(h => h.id === hostId);
    return `${i ? 'B' : 'A'} · ${host?.name || hostId || ''} · ${e.name}: ${e.error || (!MapTraffic.fresh(e, now) ? 'Leitura desatualizada' : (e.up ? 'UP' : 'DOWN'))}`;
  }).concat([`Download: ${connectionRate(data.tx)} · ${utilization(data.tx)}`,
    `Upload: ${connectionRate(data.rx)} · ${utilization(data.rx)}`,
    `Capacidade: ${connectionRate(data.capacity)}`,
    `Última coleta: ${new Date(reading.a.measuredAt).toLocaleString('pt-BR')}`]).join('\n');
}
const mappedPending = new Map();
const mappedVersions = new WeakMap();
function mappedConnectionTooltip(link) {
  const id = link.mappedConnectionId;
  const reading = connectionReadings.get(id);
  const definition = connectionDefinitions.find(c => c.id === id) || link.connection;
  const port = MapTraffic.mappedSourcePort(link, current.layout.nodes, reading, definition);
  return MapConnectionTooltip.details(reading, definition, inventory, connectionFailures.has(id),
    connectionDisplayTime(id), port?.connector === 'pon');
}
function renderMappedReadings() {
  document.querySelectorAll('.map-traffic').forEach(updateTrafficLayer);
  if (typeof MapConnectionTooltip !== 'undefined') {
    for (const link of current?.layout.links || []) {
      if (link.mappedConnectionId) MapConnectionTooltip.refresh(link.mappedConnectionId, mappedConnectionTooltip(link));
    }
  }
}
async function refreshMappedConnections(force = false) {
  if (editing || !current || document.hidden) return true;
  const requestedMap = current;
  const existing = mappedPending.get(requestedMap);
  if (existing) {
    if (!force || existing.force) return existing.promise;
    await existing.promise;
    if (current !== requestedMap || editing || document.hidden) return true;
    return refreshMappedConnections(true);
  }
  const ids = [...new Set(requestedMap.layout.links.map(link => link.mappedConnectionId).filter(Boolean))];
  if (!ids.length) return true;
  const request = {force};
  request.promise = (async () => {
    try {
      const batch = await connectionRequest('/api/maps/' + encodeURIComponent(requestedMap.id)
        + '/connections/readings' + (force ? '?force=true' : ''), ids,
        {'X-Map-Readings-Version': mappedVersions.get(requestedMap) || ''});
      if (current !== requestedMap || editing || document.hidden) return true;
      if (batch.version) mappedVersions.set(requestedMap, batch.version);
      const rows = Array.isArray(batch) ? batch : batch.unchanged
        ? ids.map(id => connectionReadings.get(id)).filter(Boolean) : batch.readings;
      const rowsById = new Map(rows.map(row => [row.id, row]));
      let ok = true;
      ids.forEach(id => {
        connectionPausedAt.delete(id);
        const reading = rowsById.get(id);
        connectionFailures.delete(id);
        if (reading) {
          connectionReadings.set(id, reading);
          if (reading.a?.queryError || reading.b?.queryError) ok = false;
        } else connectionReadings.delete(id);
      });
      return ok;
    } catch {
      if (current === requestedMap && !editing && !document.hidden) {
        ids.forEach(id => { connectionPausedAt.delete(id); connectionFailures.add(id); });
      }
      return false;
    } finally {
      if (mappedPending.get(requestedMap) === request) mappedPending.delete(requestedMap);
      if (current === requestedMap && !editing) renderMappedReadings();
    }
  })();
  mappedPending.set(requestedMap, request);
  return request.promise;
}
setInterval(refreshMappedConnections, 60000);
refreshMappedConnections();

const groupLinkEditor = typeof GroupLinkInterfaces !== 'undefined' ? GroupLinkInterfaces.create({
  hosts: () => inventory,
  discover: hostId => connectionRequest('/api/maps/connections/discover/' + encodeURIComponent(hostId) + '?includeLogical=true')
}) : null;
