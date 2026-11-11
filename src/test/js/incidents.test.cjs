const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const tick = () => new Promise(resolve => setImmediate(resolve));

async function surface(respond, options = {}) {
  class Element {
    constructor() { this.children = []; this.listeners = {}; this.dataset = {}; this.attributes = {}; this.value = ''; this.hidden = true; this.options = []; }
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; }
    setAttribute(k, v) { this.attributes[k] = v; }
    addEventListener(k, fn) { this.listeners[k] = fn; }
    emit(k) { this.listeners[k]?.({ preventDefault() {} }); }
    closest() { return this; }
    querySelector() { return this.p ||= new Element(); }
  }
  const elements = new Proxy({}, { get: (obj, id) => obj[id] ||= new Element() });
  const sorts = ['date', 'duration'].map(key => { const el = new Element(); el.dataset.incidentSort = key; return el; });
  const fields = new Map([['groupId', 'g'], ['hostId', ''], ['severity', ''], ['q', '']]);
  const severityOptions = ['Não classificada', 'Informação', 'Atenção', 'Média', 'Alta', 'Desastre'].map((label, index) => {
    const option = new Element(); option.value = String(index); option.checked = true;
    option.nextElementSibling = { textContent: label }; return option;
  });
  const storage = new Map(options.saved ? [['routiva.incidents.filters', JSON.stringify(options.saved)]] : []);
  for (const [key, id] of [['groupId', 'incident-group'], ['hostId', 'incident-host'], ['q', 'incident-query']]) {
    Object.defineProperty(elements[id], 'value', { get: () => fields.get(key), set: value => fields.set(key, value) });
  }
  elements['incident-group'].options = [{ value: 'g', textContent: 'POP', dataset: {} }];
  elements['incident-host'].options = [{ value: '1', textContent: 'Router · 10.0.0.1', dataset: { groups: 'g' } }];
  elements['incident-group'].options.push(...(options.extraGroups || []));
  elements['incident-host'].options.push(...(options.extraHosts || []));
  elements['incident-filters'].elements = { namedItem: key => elements[{ groupId: 'incident-group', hostId: 'incident-host', q: 'incident-query' }[key]] };
  const timers = new Map(), intervals = [], requests = [], events = {};
  const main = new Element(); main.dataset.timeZone = 'America/Sao_Paulo';
  const context = {
    document: { getElementById: id => elements[id], createElement: () => new Element(), createElementNS: () => new Element(), createTextNode: text => ({ textContent: text }), createDocumentFragment: () => new Element(), querySelector: () => main, addEventListener() {}, querySelectorAll: selector => selector === '[data-opened-at]' ? [] : selector === '[data-incident-severity]' ? severityOptions : sorts },
    localStorage: { getItem: key => storage.get(key) || null, setItem: (key, value) => storage.set(key, value) },
    location: { search: options.search ?? '?groupId=g&page=2' }, history: { replaceState() {} },
    FormData: class { constructor() { return fields; } }, URLSearchParams, AbortController, Intl, Date,
    setTimeout(fn, ms) { const id = timers.size + 1; timers.set(id, { fn, ms }); return id; },
    clearTimeout(id) { timers.delete(id); }, setInterval(fn) { intervals.push(fn); return intervals.length; }, clearInterval() {},
    addEventListener(key, fn) { events[key] = fn; },
    fetch: async url => { const params = new URL(url, 'http://localhost').searchParams; requests.push(params); return respond(params); },
  };
  context.window = context;
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/incidents.js', 'utf8'), context);
  await tick();
  return { elements, fields, requests, sorts, events, timers, intervals, severityOptions, storage, rows: () => elements['incident-rows'].children };
}
const row = { eventId: '1', name: '<script>original trigger</script>', severity: 4, severityLabel: 'Alta', timestamp: Date.now() - 87 * 60000, hosts: [{ name: 'Router', ip: '10.0.0.1' }] };
function response(params) { return { ok: true, json: async () => ({ rows: [row], total: 32, page: Number(params.get('page')), pages: 4, size: 10, updatedAt: Date.now(), warning: '' }) }; }

test('renders five columns safely and calculates durations locally', async () => {
  const ui = await surface(response);
  const cells = ui.rows()[0].children[0].children;
  assert.equal(cells.length, 5);
  assert.equal(cells[3].children[0].textContent, row.name);
  assert.equal(cells[3].children[0].title, row.name);
  assert.equal(cells[4].children[0].children[1].textContent, '1 h 27 min');
  assert.equal(ui.elements['incident-total'].textContent, '32 encontrados');
  assert.equal(ui.requests[0].get('page'), '2');
  ui.intervals[0](); assert.equal(ui.requests.length, 1);
});

test('refresh retains applied filters and page; submit resets page and sort works', async () => {
  const ui = await surface(response);
  ui.fields.set('q', 'unsubmitted text');
  [...ui.timers.values()].find(timer => timer.ms === 60000).fn(); await tick();
  assert.equal(ui.requests.at(-1).get('page'), '2');
  assert.equal(ui.requests.at(-1).get('q'), null);
  ui.elements['incident-filters'].emit('submit'); await tick();
  assert.equal(ui.requests.at(-1).get('q'), null);
  assert.equal(ui.requests.at(-1).get('page'), '1');
  ui.sorts[1].emit('click'); await tick();
  assert.equal(ui.requests.at(-1).get('sort'), 'duration');
  assert.equal(ui.requests.at(-1).get('direction'), 'desc');
});

test('errors keep successful rows and timestamp; retry recovers', async () => {
  let fail = false;
  const ui = await surface(params => fail ? { ok: false } : response(params));
  const rows = ui.rows(), updated = ui.elements['incident-updated'].textContent;
  fail = true; ui.elements['incident-retry'].emit('click'); await tick();
  assert.equal(ui.rows(), rows);
  assert.equal(ui.elements['incident-updated'].textContent, updated);
  assert.equal(ui.elements['incident-error'].hidden, false);
  fail = false; ui.elements['incident-retry'].emit('click'); await tick();
  assert.equal(ui.elements['incident-error'].hidden, true);
});

test('rapid clicks cannot create concurrent requests', async () => {
  let finish;
  const ui = await surface(params => new Promise(resolve => { finish = () => resolve(response(params)); }));
  ui.elements['incident-filters'].emit('submit');
  ui.elements['incident-retry'].emit('click');
  assert.equal(ui.requests.length, 1);
  finish(); await tick();
});

test('distinguishes an empty filtered result and displays partial-data warnings', async () => {
  const ui = await surface(() => ({ ok: true, json: async () => ({ rows: [], total: 0, page: 1, pages: 1, size: 10, updatedAt: Date.now(), warning: '' }) }));
  assert.equal(ui.elements['incident-empty'].hidden, false);
  assert.match(ui.elements['incident-empty'].querySelector('p').textContent, /filtros selecionados/);
  assert.equal(ui.elements['incident-warning'].hidden, true);
  const partial = await surface(params => ({ ok: true, json: async () => ({ ...(await response(params).json()), warning: 'Dados parciais' }) }));
  assert.equal(partial.elements['incident-warning'].hidden, false);
  assert.equal(partial.rows()[0].children.length, 1);
});

test('changing groups removes a host outside the selected group', async () => {
  const ui = await surface(response);
  const outside = { value: '1', selected: true, dataset: { groups: 'other' } };
  const inside = { value: '2', selected: false, dataset: { groups: 'g' } };
  ui.elements['incident-host'].options = [outside, inside];
  ui.elements['incident-host'].value = '1';
  ui.elements['incident-group'].options[0].selected = true;
  ui.elements['incident-group'].emit('change');
  assert.equal(outside.disabled, true);
  assert.equal(inside.disabled, false);
  assert.equal(outside.selected, false);
});

test('severities start all selected and each click independently toggles the filter', async () => {
  const ui = await surface(response);
  assert.ok(ui.severityOptions.every(option => option.checked));
  assert.equal(ui.requests[0].get('severity'), '');
  ui.severityOptions[2].checked = false; ui.severityOptions[2].emit('change');
  assert.equal(ui.elements['incident-severity-summary'].textContent, '5 severidades selecionadas');
  ui.elements['incident-filters'].emit('submit'); await tick();
  assert.equal(ui.requests.at(-1).get('severity'), '0,1,3,4,5');
  ui.severityOptions.forEach(option => { option.checked = false; });
  ui.elements['incident-filters'].emit('submit'); await tick();
  assert.equal(ui.requests.at(-1).get('severity'), 'none');
});

test('restores browser filters while explicit diagnostic links override saved choices', async () => {
  const saved = { groupId: 'g', hostId: '', severity: '2,4', q: 'router' };
  const ui = await surface(response, { saved, search: '' });
  assert.equal(ui.requests[0].get('q'), null);
  assert.equal(ui.requests[0].get('severity'), '2,4');
  assert.deepEqual(ui.severityOptions.filter(option => option.checked).map(option => option.value), ['2', '4']);
  ui.fields.set('q', 'new filter'); ui.elements['incident-filters'].emit('input');
  assert.equal(JSON.parse(ui.storage.get('routiva.incidents.filters')).q, undefined);
  const linked = await surface(response, { saved, search: '?groupId=g&severity=5' });
  assert.equal(linked.requests[0].get('q'), null);
  assert.equal(linked.requests[0].get('severity'), '5');
  const empty = await surface(response, { saved: { severity: 'none' }, search: '' });
  assert.ok(empty.severityOptions.every(option => !option.checked));
});

test('severity changes automatically apply filters and reset pagination', async () => {
  const ui = await surface(response);
  ui.severityOptions[2].checked = false; ui.severityOptions[2].emit('change');
  [...ui.timers.values()].find(timer => timer.ms === 300).fn(); await tick();
  assert.equal(ui.requests.at(-1).get('severity'), '0,1,3,4,5');
  assert.equal(ui.requests.at(-1).get('page'), '1');
});

test('filter changes during an active request queue the latest selection', async () => {
  const completions = [];
  const ui = await surface(params => new Promise(resolve => completions.push(() => resolve(response(params)))));
  ui.severityOptions[2].checked = false; ui.severityOptions[2].emit('change');
  [...ui.timers.values()].find(timer => timer.ms === 300).fn();
  assert.equal(ui.requests.length, 1);
  completions.shift()(); await tick();
  assert.equal(ui.requests.length, 2);
  assert.equal(ui.requests[1].get('severity'), '0,1,3,4,5');
  completions.shift()(); await tick();
  assert.equal(ui.elements['incident-error'].hidden, true);
});

test('searchable pickers allow multiple groups and hosts and persist selections', async () => {
  const ui = await surface(response, {
    extraGroups: [{ value: 'other', textContent: 'Other group', dataset: {} }],
    extraHosts: [{ value: '2', textContent: 'Other router · 10.0.0.2', dataset: { groups: 'other' } }],
  });
  const groupLabels = ui.elements['incident-group-options'].children.slice(1);
  groupLabels[1].children[0].checked = true; groupLabels[1].children[0].emit('change');
  assert.equal(ui.elements['incident-group-summary'].textContent, '2 grupos selecionados');
  const hostLabels = ui.elements['incident-host-options'].children.slice(1);
  for (const label of hostLabels) { label.children[0].checked = true; label.children[0].emit('change'); }
  assert.equal(ui.elements['incident-host-summary'].textContent, '2 hosts selecionados');
  ui.elements['incident-host-search'].value = '10.0.0.2'; ui.elements['incident-host-search'].emit('input');
  assert.equal(hostLabels[0].hidden, true);
  assert.equal(hostLabels[1].hidden, false);
  ui.elements['incident-filters'].emit('submit'); await tick();
  assert.equal(ui.requests.at(-1).get('groupId'), 'g,other');
  assert.equal(ui.requests.at(-1).get('hostId'), '1,2');
  const saved = JSON.parse(ui.storage.get('routiva.incidents.filters'));
  assert.equal(saved.hostId, '1,2');
  groupLabels[1].children[0].checked = false; groupLabels[1].children[0].emit('change');
  assert.equal(ui.elements['incident-host'].options[1].selected, false);
  assert.equal(JSON.parse(ui.storage.get('routiva.incidents.filters')).hostId, '1');
});

test('first picker option selects every available group or host even with a search term', async () => {
  const ui = await surface(response, {
    extraGroups: [{ value: 'other', textContent: 'Other group', dataset: {} }],
    extraHosts: [{ value: '2', textContent: 'Other router', dataset: { groups: 'other' } }],
  });
  const groupAll = ui.elements['incident-group-options'].children[0];
  assert.equal(groupAll.children[1].textContent, 'Todos os grupos de hosts');
  assert.equal(groupAll.children[0].indeterminate, true);
  ui.elements['incident-group-search'].value = 'POP'; ui.elements['incident-group-search'].emit('input');
  groupAll.children[0].checked = true; groupAll.children[0].emit('change');
  assert.ok(ui.elements['incident-group'].options.every(option => option.selected));
  assert.equal(groupAll.children[0].indeterminate, false);
  const hostAll = ui.elements['incident-host-options'].children[0];
  assert.equal(hostAll.children[1].textContent, 'Todos os Hosts');
  hostAll.children[0].checked = true; hostAll.children[0].emit('change');
  assert.ok(ui.elements['incident-host'].options.every(option => option.selected));
  assert.equal(JSON.parse(ui.storage.get('routiva.incidents.filters')).hostId, '1,2');
});
