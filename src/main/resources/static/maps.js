'use strict';

const $ = id => document.getElementById(id);
const labels = ['Sem dados', 'Sem incidentes', 'Não classificado', 'Informação', 'Atenção', 'Média', 'Alta', 'Desastre'];
const colors = ['#687486', '#58bd83', '#a7a9c0', '#7499ff', '#ffc859', '#ffa059', '#e97659', '#ed4545'];
let maps = [], groups = [], current, editing = false, tool = "select", selected, selectedLink, drag;
let inventory = [], pendingNode, baseline;
const past = [], future = [];
let states = new Map(), generation = 0, timer;

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

function hostLevel(host) {
  if (!host.incidents) return -2;
  return host.incidents.reduce((level, issue) => Math.max(level, issue.severity), -1);
}

function groupLevel(hosts) {
  if (!hosts.length) return -2;
  const levels = hosts.map(hostLevel);
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
  selected = selectedLink = drag = null;
  draw();
}
function nodeHosts(node) {
  return states.get(node.hostId ? 'host:' + node.hostId : node.groupId) || [];
}
function details() {
  const node = current?.layout.nodes.find(n => n.id === selected);
  $('detail').replaceChildren();
  if (!node || editing) return;
  const title = document.createElement('h2');
  title.textContent = node.name;
  $('detail').append(title);
  if (!node.groupId && !node.hostId) return;
  const hosts = [...nodeHosts(node)].sort((a,b) => hostLevel(b) - hostLevel(a));
  if (!hosts.length) $('detail').append('Sem dados de incidentes disponíveis.');
  for (const host of hosts) {
    const row = document.createElement('p');
    const issues = (host.incidents || []).map(i => labels[i.severity + 2] + ' — ' + i.name);
    row.textContent = host.name + ' · ' + (issues.join(' / ') || labels[hostLevel(host) + 2]);
    $('detail').append(row);
  }
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

function draw() {
  const canvas = $('canvas');
  canvas.replaceChildren();
  if (!current) return;
  const { nodes, links } = current.layout;
  $('map-title').textContent = (editing ? 'Editar mapa — ' : '') + current.layout.name;
  canvas.dataset.tool = editing ? tool : '';
  if (editing) {
    for (const [id, mode] of [['connect','connect'], ['select-tool','select'], ['pan-tool','pan']])
      $(id).setAttribute('aria-pressed', String(tool === mode));
    $('undo').disabled = !past.length;
    $('redo').disabled = !future.length;
    $('delete-item').disabled = !selected && !selectedLink;
    $('counts').textContent = nodes.length + ' elementos　│　' + links.length + ' conexões';
    $('hint').textContent = tool === 'connect' ? (selected ? 'Selecione o destino. Esc cancela.' : 'Selecione a origem. Esc cancela.')
      : tool === 'pan' ? 'Arraste para mover o mapa.' : 'Selecione para mover ou excluir. Clique novamente para editar.';
  }
  const byId = new Map(nodes.map(n => [n.id, n]));
  for (const link of links) {
    const a = byId.get(link.from), b = byId.get(link.to);
    const line = svg('line', {x1:a.x, y1:a.y, x2:b.x, y2:b.y});
    if (editing && tool === 'select') {
      line.classList.toggle('selected-link', selectedLink === link);
      line.setAttribute('tabindex', 0);
      line.setAttribute('role', 'button');
      line.setAttribute('aria-label', 'Conexão entre ' + a.name + ' e ' + b.name);
      line.onclick = () => { selectedLink = link; selected = null; draw(); };
      line.onkeydown = e => { if (e.key === 'Enter') line.onclick(); };
    }
    canvas.append(line);
  }
  for (const n of nodes) {
    const monitored = !editing && (n.hostId || n.groupId);
    const level = monitored ? groupLevel(nodeHosts(n)) + 2 : 1;
    const description = monitored ? n.name + ' · ' + labels[level] : n.name;
    const g = svg('g', {transform:'translate(' + n.x + ' ' + n.y + ')', tabindex:0, role:'button',
      'aria-label':description});
    const title = svg('title', {});
    title.textContent = description;
    g.append(title);
    drawNode(g, n.name, n.labelPosition, editing || monitored ? colors[level] : colors[0], selected === n.id);
    g.onkeydown = event => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); choose(n); }
    };
    g.onpointerdown = event => {
      if (event.button !== 0 || (editing && tool === 'pan')) return;
      if (editing && tool === 'select') {
        canvas.setPointerCapture(event.pointerId);
        const point = new DOMPoint(event.clientX,event.clientY).matrixTransform(canvas.getScreenCTM().inverse());
        drag = {node:n, dx:n.x-point.x, dy:n.y-point.y, pointer:event.pointerId, x:event.clientX, y:event.clientY, moved:false};
        return;
      }
      choose(n);
    };
    canvas.append(g);
  }
  details();
}

function choose(node) {
  if (editing && tool === 'pan') return;
  const origin = selected;
  selected = node.id;
  selectedLink = null;
  if (editing && tool === 'connect' && origin && origin !== node.id) {
    const pair = [origin, node.id];
    if (!current.layout.links.some(l => pair.includes(l.from) && pair.includes(l.to))) {
      checkpoint();
      current.layout.links.push({from:origin, to:node.id});
    }
    tool = 'select';
  } else if (editing && tool === 'select' && origin === node.id) {
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
$('maximize-map').onclick = async () => {
  const target = $('map-stage');
  try {
    if (document.fullscreenElement === target) await document.exitFullscreen();
    else await target.requestFullscreen();
  } catch { window.notify('Tela cheia indisponível neste navegador.', 'error'); }
};
document.addEventListener('fullscreenchange', () => {
  const mapLabel = document.fullscreenElement === $('map-stage') ? 'Restaurar mapa' : 'Maximizar mapa';
  $('maximize-map').title = mapLabel;
  $('maximize-map').setAttribute('aria-label', mapLabel);
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
$('canvas').onpointermove = event => {
  if (!drag || drag.pointer !== event.pointerId) return;
  if (!drag.moved && Math.hypot(event.clientX - drag.x, event.clientY - drag.y) < 4) return;
  const point = new DOMPoint(event.clientX, event.clientY).matrixTransform($('canvas').getScreenCTM().inverse());
  if (!drag.moved) checkpoint();
  selected = drag.node.id;
  selectedLink = null;
  drag.moved = true;
  drag.node.x = Math.round(point.x + drag.dx);
  drag.node.y = Math.round(point.y + drag.dy);
  draw();
};
$('canvas').onpointerup = event => {
  if (drag?.pointer !== event.pointerId) return;
  const {node, moved} = drag;
  drag = null;
  if (!moved) choose(node);
};
$('canvas').onlostpointercapture = () => { drag = null; };

async function refresh(token) {
  if (!current || editing || token !== generation) return;
  try {
    const ids = [...new Set(current.layout.nodes.map(n => n.groupId).filter(Boolean))];
    const hostsIds = [...new Set(current.layout.nodes.map(n => n.hostId).filter(Boolean))];
    const hosts = ids.length || hostsIds.length ? await api('/states', {groups:ids, hosts:hostsIds}) : [];
    if (token !== generation) return;
    states = new Map();
    for (const host of hosts) states.set('host:' + host.id, [host]);
    for (const host of hosts) for (const group of host.groups) {
      if (!states.has(group.id)) states.set(group.id, []);
      states.get(group.id).push(host);
    }
    $('status').textContent = ids.length || hostsIds.length ? 'Incidentes atualizados às ' + new Date().toLocaleTimeString('pt-BR') : '';
  } catch {
    if (token !== generation) return;
    states.clear();
    $('status').textContent = 'Consulta ao Zabbix indisponível. Nova tentativa em um minuto.';
  }
  draw();
  timer = setTimeout(() => refresh(token), 60000);
}

function openMap(map, edit = false) {
  clearTimeout(timer);
  generation++;
  current = map ? JSON.parse(JSON.stringify(map)) : null;
  editing = edit;
  selected = drag = null;
  tool = 'select';
  selectedLink = null;
  past.length = future.length = 0;
  baseline = map ? JSON.stringify({...current.layout, camera:current.layout.camera ?? null}) : null;
  states.clear();
  $('detail').replaceChildren();
  $('editor').hidden = !edit;
  $('library').hidden = !!map;
  $('workspace').hidden = !map;
  $('edit').hidden = edit;
  $('editor-footer').hidden = $('editor-actions').hidden = !edit;
  $('map-description').textContent = edit ? 'Edite a topologia, adicione elementos, conecte e organize o mapa.' : 'Visualização do mapa de topologia da rede.';
  $('map-title').textContent = map?.layout.name || '';
  stopMotion();
  if ((!map || edit) && document.fullscreenElement === $('map-stage')) document.exitFullscreen();
  pan = null;
  $('workspace').classList.toggle('viewing', !!map && !edit);
  $('maximize-map').hidden = edit;
  if (map) {
    viewport.scrollTo(0, 0);
    camera = map.layout.camera ? {...map.layout.camera} : {x:0, y:0, scale:1};
    syncZoom();
    resizeCanvas();
  }
  $('status').textContent = edit ? 'Editando topologia' : map ? 'Consultando incidentes…'
    : '';
  draw();
  if (map && !map.layout.camera) fitToView();
  refresh(generation);
}

const pageSize = 10;
let page = 0;
function action(label, handler) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'map-action';
  button.textContent = label;
  button.onclick = handler;
  return button;
}

function positionMapOptions() {
  const menu = $('maps').querySelector('.map-options:popover-open');
  if (!menu) return;
  const button = menu.previousElementSibling.getBoundingClientRect();
  const box = menu.getBoundingClientRect();
  const gap = 4;
  const viewport = document.documentElement;
  const below = button.bottom + gap;
  const top = below + box.height <= viewport.clientHeight ? below : button.top - gap - box.height;
  menu.style.top = Math.max(gap, Math.min(top, viewport.clientHeight - box.height - gap)) + 'px';
  menu.style.left = Math.max(gap, Math.min(button.right - box.width, viewport.clientWidth - box.width - gap)) + 'px';
}
window.addEventListener('resize', positionMapOptions);
document.addEventListener('scroll', positionMapOptions, true);

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
    const title = document.createElement('strong');
    title.textContent = layout.name;
    row.insertCell().append(title);
    for (const value of [layout.nodes.length, layout.links.length,
      map.updatedAt ? new Date(map.updatedAt).toLocaleString('pt-BR', {dateStyle:'short', timeStyle:'short'}) : '—']) {
      row.insertCell().textContent = value;
    }
    const published = layout.publication === 'PUBLICADO';
    const badge = document.createElement('span');
    badge.className = 'status-label ' + (published ? 'green' : 'gray');
    badge.textContent = published ? 'Publicado' : 'Rascunho';
    row.insertCell().append(badge);
    const actions = row.insertCell();
    const menu = document.createElement('div');
    menu.className = 'map-options utility-panel';
    menu.popover = 'auto';
    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'map-options-toggle';
    toggle.popoverTargetElement = menu;
    menu.addEventListener('toggle', event => {
      if (event.newState === 'open') positionMapOptions();
    });
    toggle.setAttribute('aria-label', 'Mais opções para ' + layout.name);
    const dots = svg('svg', {viewBox:'0 0 24 24', width:18, height:18, fill:'currentColor', 'aria-hidden':true});
    for (const cy of [5, 12, 19]) dots.append(svg('circle', {cx:12, cy, r:1.8}));
    toggle.append(dots);
    menu.addEventListener('click', () => menu.hidePopover());
    menu.append(action('Editar', () => openMap(map, true)),
      action(published ? 'Mover para rascunho' : 'Publicar', async event => {
        event.currentTarget.disabled = true;
        try {
          await persist({...map, layout:{...layout, publication:published ? 'RASCUNHO' : 'PUBLICADO'}});
        } catch (error) { $('status').textContent = error.message; renderList(); }
      }));
    const view = action('Visualizar', () => openMap(map));
    view.classList.add('map-view');
    const icon = svg('svg', {viewBox:'0 0 24 24', width:16, height:16, fill:'none',
      stroke:'currentColor', 'stroke-width':1.8, 'stroke-linecap':'round', 'stroke-linejoin':'round', 'aria-hidden':true});
    icon.append(svg('path', {d:'M14 3h7v7m0-7L11 13m-1-9H5a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h13a2 2 0 0 0 2-2v-5'}));
    view.prepend(icon);
    const controls = document.createElement('div');
    controls.className = 'map-row-actions';
    controls.append(view, toggle, menu);
    actions.append(controls);
  }
  $('maps-empty').hidden = rows.length > 0;
  $('map-count').textContent = 'Mostrando ' + rows.length + ' de ' + maps.length + ' mapas';
  $('previous').hidden = $('next').hidden = filtered.length <= pageSize;
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
  $('submit-create').textContent = 'Criando…';
  try {
    const saved = await persist({layout:{name, publication:'RASCUNHO', nodes:[], links:[]}});
    createDialog.close();
    openMap(saved, true);
    $('add').focus();
  } catch (error) {
    $('create-error').textContent = error.message;
    $('create-name').focus();
  } finally {
    createFields.forEach(field => { field.readOnly = false; });
    $('submit-create').disabled = $('cancel-create').disabled = false;
    $('submit-create').textContent = 'Criar mapa';
    createForm.removeAttribute('aria-busy');
  }
};

function showProperties() {
  const layout = current?.layout;
  $('map-error').textContent = '';
  $('name').value = layout?.name || '';
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
$('back').onclick = () => { if (mayLeave()) openMap(null); };
$('edit').onclick = () => openMap(current, true);
$('cancel').onclick = () => { if (mayLeave()) openMap(maps.find(m => m.id === current.id)); };

function setTool(mode) {
  tool = mode;
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
function drawPreview() {
  const preview = $('node-preview');
  const group = svg('g', {});
  preview.replaceChildren(group);
  const name = $('element-name').value.trim() || 'Nome do elemento';
  drawNode(group, name, $('label-position').value, colors[0]);
  const box = group.getBBox();
  const w = Math.max(140, box.width + 20), h = Math.max(66, box.height + 12);
  preview.setAttribute('viewBox', [box.x + (box.width-w)/2, box.y + (box.height-h)/2, w, h].join(' '));
  preview.setAttribute('aria-label', 'Círculo · ' + name);
}

function openElement(node, type = node.hostId ? 'host' : node.groupId ? 'group' : '') {
  $('element-name').value = node.name;
  $('label-position').value = node.labelPosition || 'BELOW';
  $('type').value = type;
  $('remove').hidden = !!pendingNode;
  populateBinding(type,node.hostId || node.groupId);
  $('element-dialog').removeAttribute('style');
  $('element-dialog').showModal();
  drawPreview();
  $('element-name').focus();
}
$('element-name').oninput = $('label-position').onchange = drawPreview;
$('type').onchange = () => {
  populateBinding($('type').value);
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
  checkpoint();
  Object.assign(node, {name, labelPosition:$('label-position').value, hostId:type === 'host' ? binding : null,
    groupId:type === 'group' ? binding : null, color:null});
  if (pendingNode) current.layout.nodes.push(node);
  pendingNode = null;
  selected = node.id;
  selectedLink = null;
  tool = 'select';
  $('element-dialog').close();
  draw();
  clearTimeout(timer);
  refresh(++generation);
};
function deleteSelection() {
  if ((!selected && !selectedLink) || !confirm(selectedLink ? 'Excluir a conexão?' : 'Excluir o elemento e suas conexões?')) return;
  checkpoint();
  if (selectedLink) current.layout.links = current.layout.links.filter(l => l !== selectedLink);
  else {
    current.layout.nodes = current.layout.nodes.filter(n => n.id !== selected);
    current.layout.links = current.layout.links.filter(l => l.from !== selected && l.to !== selected);
  }
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

function menu(open) {
  document.body.classList.toggle('availability-menu-open', open);
  $('menu-backdrop').hidden = !open;
  $('mobile-menu').setAttribute('aria-expanded', String(open));
}
$('mobile-menu').onclick = () => menu($('menu-backdrop').hidden);
$('menu-backdrop').onclick = () => menu(false);

(async () => {
  try {
    [maps, groups, inventory] = await Promise.all([api(''), api('/groups'), api('/hosts')]);
    renderList();
    $('create').disabled = false;
    openMap(null);
  } catch { $('status').textContent = 'Falha ao carregar os mapas. Recarregue a página para tentar novamente.'; }
})();
