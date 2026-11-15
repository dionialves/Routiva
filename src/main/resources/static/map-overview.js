'use strict';

// Presentation only: classifications come from the map's existing metric/incident rules.
function summarizeMapNodes(nodes, view, stateOf, loaded) {
  const monitored = nodes.filter(node => node.hostId || node.groupId);
  const result = {total:monitored.length, data:loaded ? 0 : null, attention:loaded ? 0 : null, unknown:loaded ? 0 : null};
  if (!loaded) return result;
  for (const node of monitored) {
    const state = stateOf(node);
    const missing = view === 'incidents' ? state === -2 : !['NORMAL', 'ATENCAO', 'CRITICO'].includes(state);
    if (missing) result.unknown++;
    else result.data++;
    if (view === 'incidents' ? state === 2 : state === 'ATENCAO') result.attention++;
  }
  return result;
}

function mapNodeStatuses(nodes, view, stateOf, loaded, colors, labels) {
  if (!loaded) return [];
  const counts = new Map();
  for (const node of nodes.filter(node => node.hostId || node.groupId)) {
    let state = stateOf(node);
    if (view !== 'incidents' && !['NORMAL', 'ATENCAO', 'CRITICO'].includes(state)) state = 'SEM_DADOS';
    counts.set(state, (counts.get(state) || 0) + 1);
  }
  const options = view === 'incidents'
    ? labels.map((label, index) => ({state:index - 2, label, color:colors[index]}))
    : [{state:'NORMAL', label:'Normal', color:'#58bd83'}, {state:'ATENCAO', label:'Atenção', color:'#fced4e'},
      {state:'CRITICO', label:'Crítico', color:'#ff0000'}, {state:'SEM_DADOS', label:'Sem dados', color:'#687486'}];
  return options.filter(item => counts.has(item.state)).map(item => ({...item, count:counts.get(item.state)}));
}

function renderMapOverview({nodes, view, stateOf, updatedAt, failed, busy, colors, labels}) {
  const get = id => document.getElementById(id);
  const names = {incidents:'Incidentes', latency:'Latência', loss:'Perda de pacotes'};
  const paths = {
    incidents:'M12 3 2 21h20L12 3Zm0 5v6m0 3v1',
    latency:'M3 18 9 12l4 3 8-10m-6 0h6v6',
    loss:'M3 5h6v6H3V5Zm12 8h6v6h-6v-6ZM9 8h5m3 0h4M3 16h5m3 0h4',
  };
  get('map-active-name').textContent = names[view];
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', paths[view]);
  get('map-active-icon').replaceChildren(path);
  document.querySelectorAll('[data-map-view]').forEach(button => {
    button.setAttribute('aria-pressed', String(button.dataset.mapView === view));
  });
  const totals = summarizeMapNodes(nodes, view, stateOf, updatedAt > 0);
  get('map-node-total').textContent = totals.total;
  const legend = mapNodeStatuses(nodes, view, stateOf, updatedAt > 0, colors, labels);
  const totalCard = get('map-node-total').parentElement;
  const cards = legend.map(item => {
    const card = document.createElement('div');
    const dot = document.createElement('i');
    dot.style.backgroundColor = item.color;
    dot.setAttribute('aria-hidden', 'true');
    const label = document.createElement('dt');
    label.textContent = item.label;
    const count = document.createElement('dd');
    count.textContent = item.count;
    card.append(dot, label, count);
    return card;
  });
  get('map-node-indicators').replaceChildren(totalCard, ...cards);
  const badges = legend.map(item => {
    const badge = document.createElement('span');
    const dot = document.createElement('i');
    dot.style.backgroundColor = item.color;
    dot.setAttribute('aria-hidden', 'true');
    badge.append(dot, document.createTextNode(item.label));
    return badge;
  });
  get('map-state-legend').replaceChildren(...badges);
  const time = get('map-updated-at');
  time.textContent = updatedAt ? new Date(updatedAt).toLocaleString('pt-BR', {dateStyle:'short', timeStyle:'short'}) : 'Aguardando dados';
  if (updatedAt) time.dateTime = new Date(updatedAt).toISOString();
  else time.removeAttribute('datetime');
  get('map-updated').setAttribute('aria-busy', String(busy));
  get('map-update-warning').hidden = !failed && !(updatedAt && Date.now() - updatedAt > 90000);
}

if (typeof module !== 'undefined') module.exports = {summarizeMapNodes, mapNodeStatuses};
