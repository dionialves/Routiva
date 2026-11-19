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
let pendingLink = null, lastConnectionClick = null;
let suppressConnectionUntil = 0;
let savingConnection = false;
let states = new Map(), generation = 0, timer;
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
  const label = svg('text', labelPositions[position] || labelPositions.BELOW);
  label.textContent = name;
  group.append(svg('circle', {r:12, fill, stroke:isSelected ? 'var(--cyan)' : 'none',
    'stroke-width':3}), label);
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
  if (typeof MapConnectionTooltip !== 'undefined') MapConnectionTooltip.hide();
  const canvas = $('canvas');
  const focusedPort = document.activeElement?.getAttribute('data-port-id');
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
  for (const link of links) {
    const a = byId.get(link.from), b = byId.get(link.to);
    if (!a || !b) continue;
    const start = MapSwitch.endpoint(a, link.fromPort), end = MapSwitch.endpoint(b, link.toPort);
    const d = MapLinks.path(link, start, end);
    const line = svg('path', {d, class: 'map-connector'});
    if (editing && tool === 'select') {
      line.onpointerdown = event => {
        if (event.button !== 0) return;
        event.stopPropagation();
        if (link.style !== 'corners') return;
        drag = {kind: 'new-bend', link, pointer: event.pointerId, x: event.clientX, y: event.clientY,
          start: canvasPoint(event), moved: false};
      };
      line.classList.toggle('selected-link', selectedLink === link);
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
        if (!editing || selectedLink === link) return;
        selectedLink = link; selected = null; selection.clear(); draw();
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
      const tooltipData = () => MapConnectionTooltip.details(connectionReadings.get(link.mappedConnectionId),
        connectionDefinitions.find(c => c.id === link.mappedConnectionId), inventory, connectionFailed);
      for (const path of [line, hit]) {
        path.dataset.connectionTooltip = link.mappedConnectionId;
        path.onpointermove = event => MapConnectionTooltip.show(link.mappedConnectionId, path,
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
      if (!editing) {
        line.setAttribute('tabindex', 0);
        line.setAttribute('role', 'button');
        line.setAttribute('aria-label', 'Abrir conexão mapeada entre ' + a.name + ' e ' + b.name);
        line.onclick = hit.onclick = event => line.onfocus(event);
        line.onkeydown = event => { if (event.key === 'Enter') line.onclick(); };
      }
    }
    connectors.append(line, hit);
    if (link.mappedConnectionId && !editing) {
      const layer = svg('g', {class: 'map-traffic', 'aria-hidden': 'true'});
      connectors.append(layer);
      layer.trafficContext = {line, link, route: MapLinks.points(link, start, end), nodes};
      updateTrafficLayer(layer);
    }
  }
  const groupCircles = [];
  for (const n of nodes) {
    const monitored = !editing && (n.hostId || n.groupId);
    const level = monitored ? groupLevel(nodeHosts(n)) + 2 : 1;
    const metric = monitored ? metricSummary(n) : null;
    const description = metric ? `${n.name} · ${metric.label}: ${metric.formatted} · ${metric.state}` : monitored ? n.name + ' · ' + labels[level] : n.name;
    const g = svg('g', {transform:'translate(' + n.x + ' ' + n.y + ')',
      ...(!editing && n.groupId && n.subMapId ? {tabindex:0, role:'link'} : editing ? {tabindex:0, role:'button', 'aria-pressed':String(selection.has(n.id) || selected === n.id)} : {role:'img'}), 'aria-label':description});
    const title = svg('title', {});
    title.textContent = description;
    g.append(title);
    if (['switch', 'radio'].includes(n.icon)) MapSwitch.draw(svg, g, n, metric ? metric.color : colors[level], selection.has(n.id) || selected === n.id, {
      connectedPorts: MapSwitch.connectedPorts(n, links),
      pingState: editing ? 'unknown' : pingStateFor(n.hostId),
      interactive: editing, connect: tool === 'connect', origin: connectionOrigin?.nodeId === n.id ? connectionOrigin.portId : null,
      onPort: portId => connectEndpoint(n, portId)
    });
    else drawNode(g, n.name, n.labelPosition, metric ? metric.color : !monitored && n.color ? n.color : editing || monitored ? colors[level] : colors[0], selection.has(n.id) || selected === n.id);
    g.onkeydown = event => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); choose(n, event.shiftKey || event.ctrlKey || event.metaKey); }
    };
    g.onpointerdown = event => {
      if (event.button !== 0 || (editing && tool === 'pan')) return;
      if (!editing && n.groupId && n.subMapId) { event.stopPropagation(); return; }
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
    if (!editing && n.groupId && n.subMapId) g.onclick = event => { event.stopPropagation(); choose(n); };
    if (monitored) popSummary.bind(g, n);
    if (n.groupId && !['switch', 'radio'].includes(n.icon)) groupCircles.push(g);
    else canvas.append(g);
  }
  canvas.append(connectors);
  for (const circle of groupCircles) canvas.append(circle);
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
    if (!['switch', 'radio'].includes(node.icon) || node.interfacesEnabled === false || !node.ports?.length) connectEndpoint(node, null);
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
    target = {
      x:box.x + box.width / 2, y:box.y + box.height / 2,
      scale:Math.min(maxZoom, (w - 2 * padding) / (box.width + 4), (h - 2 * padding) / (box.height + 4))
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
function changeZoom(scale) {
  stopMotion();
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
  if (event.key === 'Escape' && !document.fullscreenElement) setMapExpanded(false);
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
    const snapped = MapLinks.snap(linkRoute(drag.link), drag.index + 1, point, camera.scale);
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
  if (drag.kind === 'new-bend') { drag = null; return; }
  if (drag.kind === 'bend' && drag.moved) suppressConnectionUntil = Date.now() + 400;
  if (drag.kind === 'bend') { const redraw = drag.moved || drag.guides?.length; drag = null; if (redraw) draw(); return; }
  const {node, moved, wasSingle} = drag;
  drag = null;
  if (!moved && wasSingle) openElement(node);
  draw();
};
function endDrag() {
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
  return refresh(++generation);
};

async function refresh(token) {
  if (!current || editing || token !== generation) return;
  clearTimeout(timer);
  setRefreshBusy(true);
  updateMapOverview();
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
function updatePingIndicators() {
  for (const led of $('canvas').querySelectorAll('.switch-status-led')) {
    const status = MapSwitch.pingIndicator(editing ? 'unknown' : pingStateFor(led.dataset.pingHost));
    led.setAttribute('fill', status.color); led.setAttribute('aria-label', status.label);
    led.querySelector('title').textContent = status.label;
  }
}
async function refreshPingState(token) {
  if (!current || editing || token !== pingGeneration) return;
  clearTimeout(pingTimer);
  const hosts = [...new Set(current.layout.nodes.filter(n => ['switch', 'radio'].includes(n.icon)).map(n => n.hostId).filter(Boolean))];
  if (!hosts.length) return;
  try {
    const readings = await api('/ping-status', {hosts, groups: []});
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
  $('refresh-map').hidden = $('fullscreen-map').hidden = $('maximize-map').hidden = edit;
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
  refreshMappedConnections(true);
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

function showProperties() {
  const layout = current?.layout;
  $('map-error').textContent = '';
  $('name').value = layout?.name || '';
  $('minimum-severity').value = String(layout?.minimumSeverity ?? 0);
  $('show-acknowledged').checked = layout?.showAcknowledged ?? true;
  $('initial-view').querySelector('[value=saved]').hidden = !layout.camera;
  $('initial-view').value = followingFit ? 'fit' : 'current';
  $('map-dialog').showModal();
  $('name').focus();
}
$('properties').onclick = showProperties;
$('close-map').onclick = () => $('map-dialog').close();
$('map-form').onsubmit = async event => {
  event.preventDefault();
  const name = $('name').value.trim();
  if (!name) { $('map-error').textContent = 'Informe o nome do mapa.'; return; }
  checkpoint();
  current.layout.name = name;
  current.layout.minimumSeverity = Number($('minimum-severity').value);
  current.layout.showAcknowledged = $('show-acknowledged').checked;
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
  } else if (key === 'escape') setTool('select');
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
  linkEndpointsReversed = !linkEndpointsReversed;
  updateLinkEndpoints();
}
$('swap-connection-endpoints').onclick = swapLinkEndpoints;
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
    const identity = name + (id ? ' · ID ' + id : '');
    $('link-' + side).value = name;
    $('link-' + side).title = name;
    const interfaceName = (mapped ? selectedInterface : port?.name) || 'Interface não selecionada';
    $('link-' + side + '-interface').textContent = interfaceName + (id ? ' · ID ' + id : '');
    const speed = (!mapped || selectedInterface === port?.name) && port?.speed;
    $('link-' + side + '-capacity').hidden = !speed || /^(unknown|auto|0(?:bps)?)$/i.test(speed);
    $('link-' + side + '-capacity').textContent = speed || '';
    $('connection-host-' + (index ? 'b' : 'a')).textContent = identity;
    $('connection-host-' + (index ? 'b' : 'a')).title = identity;
  });
}
function drawLinkPreview() {
  const link = {style: $('link-dialog-type').value, lineStyle: $('link-dialog-style').value,
    points: [{x: 180, y: 110}, {x: 620, y: 110}, {x: 620, y: 30}]};
  $('link-preview').replaceChildren(svg('path', {d: MapLinks.path(link, {x: 70, y: link.style === 'corners' ? 30 : 70}, {x: 730, y: link.style === 'corners' ? 30 : 70}),
    fill: 'none', stroke: '#8995aa', 'stroke-width': 3, 'vector-effect': 'non-scaling-stroke'}));
}
function openLinkDialog(link) {
  if (!editing || $('link-dialog').open) return;
  pendingLink = link;
  linkEndpointsReversed = false;
  selectedLink = link; selected = null; selection.clear();
  $('link-from').value = connectionEndpointLabel(link, true);
  $('link-to').value = connectionEndpointLabel(link, false);
  $('link-dialog-type').value = link.style || 'straight';
  $('link-dialog-style').value = link.lineStyle || 'straight';
  draw(); drawLinkPreview();
  $('link-dialog').showModal();
  $('link-category').value = link.mappedConnectionId ? 'mapped' : 'simple';
  loadConnectionInterfaces();
  updateLinkEndpoints();
  $('delete-link').hidden = !current.layout.links.includes(link);
  $('link-dialog-type').focus();
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
      if (!a?.hostId || !b?.hostId) throw new Error('Selecione dois elementos vinculados a hosts.');
      const name = $('connection-name').value.trim();
      const interfaceA = $('connection-interface-a').value, interfaceB = $('connection-interface-b').value;
      if (!name || !interfaceA || !interfaceB) throw new Error('Preencha o nome e as duas interfaces.');
      const existing = connectionDefinitions.find(c => c.id === target.mappedConnectionId);
      if (!existing || existing.name !== name || existing.interfaceA !== interfaceA || existing.interfaceB !== interfaceB || existing.hostA !== a.hostId || existing.hostB !== b.hostId) {
        savingConnection = true;
        const saved = await connectionRequest('/api/maps/connections/validate', {name, hostA:a.hostId, hostB:b.hostId, interfaceA, interfaceB});
        if (pendingLink !== target || !current.layout.links.includes(target)) return;
        mappedConnectionId = saved.id; connection = saved;
      }
    } catch (error) { $('connection-message').textContent = error.message; return; }
    finally { savingConnection = false; }
  } else { mappedConnectionId = undefined; }
  const style = $('link-dialog-type').value, lineStyle = $('link-dialog-style').value;
  const appearanceChanged = target.style !== style || (target.lineStyle || 'straight') !== lineStyle;
  if (linkEndpointsReversed || mappedConnectionId !== target.mappedConnectionId || appearanceChanged) checkpoint();
  if (mappedConnectionId) { target.mappedConnectionId = mappedConnectionId; target.connection = connection; }
  else { delete target.mappedConnectionId; delete target.connection; }
  if (linkEndpointsReversed) {
    const draft = linkEndpointDraft();
    Object.assign(target, {from:draft.from, to:draft.to, fromPort:draft.fromPort, toPort:draft.toPort});
    if (target.points) target.points = [...target.points].reverse();
  }
  if (appearanceChanged) {
    const resetPoints = (pendingLink.lineStyle || 'straight') !== lineStyle;
    pendingLink.style = style;
    pendingLink.lineStyle = lineStyle;
    if (resetPoints) pendingLink.points = [];
    pendingLink.points ||= [];
  }
  $('link-dialog').close(); draw();
};
function switchDraft() {
  const ports = MapSwitch.portsForSlots(configuredPorts, configuredSlots);
  return $('element-icon').value === 'radio' && ports?.length > 4 ? null : ports;
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
    connector.onchange = () => { slot.connector = connector.value; drawPreview(); };
    field('Conector', connector);
    const capacity = document.createElement('select');
    capacity.add(new Option('Não identificada', 'unknown'));
    for (const [value, label] of Object.entries(MapSwitch.portCapacities)) capacity.add(new Option(label, value));
    capacity.value = slot.capacity;
    capacity.onchange = () => { slot.capacity = capacity.value; drawPreview(); };
    field('Capacidade', capacity);
    const count = document.createElement('input');
    count.readOnly = !!slot.interfaceNames;
    count.type = 'number'; count.min = 1; count.max = 96; count.required = true; count.value = slot.count;
    count.oninput = () => { slot.count = Number(count.value); drawPreview(); };
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
        failureMessage = 'Não foi possível carregar as interfaces. Configure e valide a autenticação do host e tente novamente.';
      throw new Error();
    }
    if (generation !== interfaceLoadGeneration || !$('element-dialog').open
        || $('type').value !== 'host' || $('group').value !== hostId) return;
    if (!Array.isArray(data)) throw new Error();
    failureMessage = 'As interfaces foram recebidas, mas não foi possível montar as portas no navegador. Atualize a página para carregar a versão atual do mapa.';
    const imported = MapSwitch.importInterfaces(data, previous);
    if (!imported.ports.length) {
      failureMessage = 'Nenhuma interface física encontrada. A configuração atual foi preservada.';
      throw new Error();
    }
    if ($('element-icon').value === 'radio' && imported.ports.length > 4) {
      failureMessage = 'O host possui mais de 4 interfaces. Configure manualmente até 4 interfaces para este rádio. A configuração atual foi preservada.';
      throw new Error();
    }
    configuredSlots = imported.slots;
    configuredPorts = imported.ports;
    renderSwitchSlots(); drawPreview();
    $('interface-load-status').textContent = imported.ports.length + ' interfaces carregadas. Salve o elemento para aplicar.'
      + (imported.ports.some(port => port.capacity === 'unknown') ? ' Revise os conectores e as capacidades não identificados.' : '');
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
  if ($('element-icon').value === 'radio' && configuredSlots.reduce((sum, slot) => sum + slot.count, 0) >= 4) return;
  configuredSlots.push({id: MapSwitch.newId(), name: '', connector: 'electrical', capacity: '1g', count: 1});
  renderSwitchSlots(); drawPreview();
  $('switch-slots').lastElementChild.querySelector('input').focus();
};
function connectEndpoint(node, portId) {
  if (!editing) return;
  if (tool !== 'connect') { setTool('connect'); }
  const occupied = (id, port) => port && current.layout.links.some(link =>
    (link.from === id && link.fromPort === port) || (link.to === id && link.toPort === port));
  if (occupied(node.id, portId)) { $('hint').textContent = 'Esta porta já possui uma conexão.'; return; }
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
function drawPreview() {
  const preview = $('node-preview');
  const group = svg('g', {});
  preview.replaceChildren(group);
  const name = $('element-name').value.trim() || 'Nome do elemento';
  const icon = $('element-icon').value;
  const isRadio = icon === 'radio';
  const isSwitch = ['switch', 'radio'].includes(icon);
  $('element-color-note').hidden = isSwitch;
  $('switch-color-field').hidden = !isSwitch || isRadio;
  $('reset-switch-color').disabled = !configuredSwitchColor;
  const interfacesEnabled = $('interfaces-enabled').checked;
  $('load-switch-interfaces').hidden = !isSwitch || $('type').value !== 'host';
  $('load-switch-interfaces').disabled = loadingInterfaces || !$('group').value;
  $('switch-config').hidden = !interfacesEnabled;
  $('switch-config').disabled = !interfacesEnabled;
  $('add-switch-slot').disabled = isRadio && configuredSlots.reduce((sum, slot) => sum + slot.count, 0) >= 4;
  const ports = switchDraft();
  $('port-error').textContent = interfacesEnabled && !ports ? (isRadio ? 'O rádio permite no máximo 4 interfaces. Remova ou ajuste os slots excedentes.' : 'Preencha o nome e a capacidade de cada slot e informe de 1 a 96 portas por slot.') : '';
  const binding = {hostId: $('type').value === 'host' ? $('group').value : null,
    groupId: $('type').value === 'group' ? $('group').value : null};
  const monitored = !!(binding.hostId || binding.groupId);
  const color = monitored ? metricSummary(binding)?.color || colors[groupLevel(nodeHosts(binding)) + 2] : colors[0];
  if (isSwitch) MapSwitch.draw(svg, group, {icon, name, labelPosition: $('label-position').value,
    ports: ports || [], interfacesEnabled, switchColor: configuredSwitchColor}, color, false,
    {connectedPorts: MapSwitch.connectedPorts({id: configuredNodeId}, current.layout.links)});
  else drawNode(group, name, $('label-position').value, color);
  const box = group.getBBox();
  const w = Math.max(140, box.width + 20), h = Math.max(66, box.height + 12);
  preview.setAttribute('viewBox', [box.x + (box.width-w)/2, box.y + (box.height-h)/2, w, h].join(' '));
  preview.setAttribute('aria-label', (isRadio ? 'Rádio' : isSwitch ? 'Switch' : 'Círculo') + ' · ' + name);
}

function openElement(node, type = node.hostId ? 'host' : node.groupId ? 'group' : '') {
  interfaceLoadGeneration++; loadingInterfaces = false;
  $('interface-load-status').textContent = '';
  configuredPorts = node.ports || [];
  configuredNodeId = node.id;
  configuredSwitchColor = node.switchColor || null;
  $('switch-color').value = configuredSwitchColor || '#e0e3e8';
  $('element-icon').value = node.icon || 'circle';
  configuredSlots = MapSwitch.slotsFor(node).map(MapSwitch.normalizeSlot);
  $('interfaces-enabled').checked = node.interfacesEnabled ?? !!(node.ports?.length || node.slots?.length);
  renderSwitchSlots();
  $('element-name').value = node.name;
  $('label-position').value = node.labelPosition || 'BELOW';
  $('type').value = type;
  syncElementIcons();
  $('remove').hidden = !!pendingNode;
  populateBinding(type,node.hostId || node.groupId);
  syncSubMap(node.subMapId || '');
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
$('type').onchange = () => {
  syncElementIcons();
  populateBinding($('type').value);
  syncSubMap();
  drawPreview();
};
$('group').addEventListener('change', () => {
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
  const icon = $('element-icon').value;
  const interfacesEnabled = $('interfaces-enabled').checked;
  const ports = switchDraft();
  if (!ports) {
    drawPreview();
    $('port-error').textContent = (icon === 'radio' ? 'O rádio permite no máximo 4 interfaces. Revise os nomes, capacidades e quantidades dos slots.' : 'Existem slots inválidos. Habilite as interfaces e preencha nome, capacidade e quantidade de 1 a 96 portas.');
    $('port-error').scrollIntoView({block: 'nearest'});
    return;
  }
  const ids = new Set(ports.map(port => port.id));
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
  Object.assign(node, {icon, switchColor: configuredSwitchColor, interfacesEnabled, ports, slots: configuredSlots.map(slot => ({...slot, name: slot.name.trim()})), name, labelPosition:$('label-position').value, hostId:type === 'host' ? binding : null,
    groupId:type === 'group' ? binding : null, subMapId:type === 'group' ? $('sub-map').value || null : null, color:node.color || null});
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


let connectionDefinitions = [], connectionReadings = new Map(), connectionBusy = false, connectionFailed = false;
async function connectionRequest(url, body) {
  const response = await fetch(url, body === undefined ? {} : {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)});
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
    if (nodes.some(n => !n?.hostId)) throw new Error('Conexões mapeadas exigem dois elementos vinculados a hosts.');
    connectionDefinitions = current.layout.links.map(link => link.connection).filter(Boolean);
    const existing = connectionDefinitions.find(c => c.id === link.mappedConnectionId);
    $('connection-name').value = existing?.name || nodes.map(n => n.name).join(' ↔ ');
    const interfaces = await Promise.all(nodes.map(n => connectionRequest('/api/maps/connections/interfaces/' + encodeURIComponent(n.hostId))));
    if (sequence !== interfaceLoad || pendingLink !== link) return;
    interfaces.forEach((rows, i) => {
      controls[i].append(new Option('Selecione a interface', ''));
      rows.forEach(row => controls[i].append(new Option(row.comment?.trim() ? row.name + ' - ' + row.comment.trim() : row.name, row.name)));
      controls[i].value = (existing && (nodes[i].hostId === existing.hostA ? existing.interfaceA : existing.interfaceB)) || '';
      controls[i].disabled = false;
    });
    updateLinkEndpoints();
    $('connection-message').textContent = interfaces.some(rows => !rows.length) ? 'Nenhuma interface Ethernet disponível.' : '';
  } catch (error) { if (sequence === interfaceLoad) $('connection-message').textContent = error.message; }
  finally { if (sequence === interfaceLoad) {
    $('connection-retry').removeAttribute('aria-busy');
    $('swap-connection-endpoints').disabled = controls.some(control => control.disabled);
  } }
}
$('link-category').onchange = () => { drawLinkPreview(); loadConnectionInterfaces(); };
$('connection-interface-a').onchange = $('connection-interface-b').onchange = () => updateLinkEndpoints();
$('connection-retry').onclick = loadConnectionInterfaces;
function connectionRate(value) { return MapTraffic.rate(value); }
function updateTrafficLayer(layer) {
  const {line, link, route, nodes} = layer.trafficContext;
  const definition = connectionDefinitions.find(c => c.id === link.mappedConnectionId);
  const from = nodes.find(n => n.id === link.from);
  const reference = connectionReadings.get(link.mappedConnectionId)?.hostA || definition?.hostA;
  MapTraffic.render(svg, layer, line, route, nodes, connectionReadings.get(link.mappedConnectionId),
    $('traffic-animation').checked && !document.hidden, Boolean(reference && reference !== from?.hostId));
}
try { $('traffic-animation').checked = localStorage.getItem('map-traffic-animation') !== 'false'; } catch {}
$('traffic-animation').onchange = () => {
  try { localStorage.setItem('map-traffic-animation', String($('traffic-animation').checked)); } catch {}
  document.querySelectorAll('.map-traffic').forEach(updateTrafficLayer);
};
document.addEventListener('visibilitychange', () => document.querySelectorAll('.map-traffic').forEach(updateTrafficLayer));
function mappedConnectionTitle(id) {
  const reading = connectionReadings.get(id);
  if (!reading) return connectionFailed ? 'Conexão mapeada · Consulta indisponível' : 'Conexão mapeada · Aguardando coleta';
  const definition = connectionDefinitions.find(c => c.id === id);
  const data = MapTraffic.state(reading);
  const utilization = value => value == null || !data.capacity ? 'Sem dados' : (value / data.capacity * 100).toLocaleString('pt-BR', {maximumFractionDigits: 2}) + '%';
  return [reading.a, reading.b].map((e, i) => {
    const hostId = i ? reading.hostB || definition?.hostB : reading.hostA || definition?.hostA;
    const host = inventory.find(h => h.id === hostId);
    return `${i ? 'B' : 'A'} · ${host?.name || hostId || ''} · ${e.name}: ${Date.now() - e.measuredAt > 30000 ? 'Leitura desatualizada' : e.error || (e.up ? 'UP' : 'DOWN')}`;
  }).concat([`Download: ${connectionRate(data.tx)} · ${utilization(data.tx)}`,
    `Upload: ${connectionRate(data.rx)} · ${utilization(data.rx)}`,
    `Capacidade: ${connectionRate(data.capacity)}`,
    `Última coleta: ${new Date(reading.a.measuredAt).toLocaleString('pt-BR')}`]).join('\n');
}
async function refreshMappedConnections(warmup = false) {
  document.querySelectorAll('.map-traffic').forEach(updateTrafficLayer);
  if (connectionBusy || editing || !current || document.hidden) return;
  const ids = [...new Set(current.layout.links.map(l => l.mappedConnectionId).filter(Boolean))];
  if (!ids.length) return;
  connectionBusy = true;
  const requestedMap = current;
  try {
    const rows = [];
    for (let offset = 0; offset < ids.length; offset += 100)
      rows.push(...await connectionRequest('/api/maps/' + encodeURIComponent(requestedMap.id) + '/connections/readings', ids.slice(offset, offset + 100)));
    if (current !== requestedMap || editing) return;
    connectionFailed = false;
    connectionReadings = new Map(rows.map(r => [r.id, r]));
    if (warmup && rows.some(row => [row.a, row.b].some(endpoint => endpoint && !endpoint.error
      && endpoint.up && (endpoint.txBps == null || endpoint.rxBps == null)))) {
      setTimeout(() => {
        if (current === requestedMap && !editing) return refreshMappedConnections();
      }, 1200);
    }
  }
  catch { if (current === requestedMap) { connectionReadings.clear(); connectionFailed = true; } }
  finally {
    connectionBusy = false;
    document.querySelectorAll('.map-traffic').forEach(updateTrafficLayer);
    if (typeof MapConnectionTooltip !== 'undefined') ids.forEach(id => {
      MapConnectionTooltip.refresh(id, MapConnectionTooltip.details(connectionReadings.get(id), connectionDefinitions.find(c => c.id === id), inventory, connectionFailed));
    });
  }
}
setInterval(refreshMappedConnections, 10000);
refreshMappedConnections();
