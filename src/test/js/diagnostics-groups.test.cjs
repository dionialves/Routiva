const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const tick = () => new Promise(resolve => setImmediate(resolve));

async function surface(respond, saved) {
  class Element {
    constructor() { this.children = []; this.listeners = {}; this.value = ''; this.options = []; this.dataset = {}; this.style = {}; this.classList = { add() {}, remove() {} }; this.attributes = {}; }
    append(...items) { this.children.push(...items); }
    replaceChildren(...items) { this.children = items; }
    setAttribute(key, value) { this.attributes[key] = value; }
    addEventListener(name, fn) { this.listeners[name] = fn; }
    emit(name) { this.listeners[name]?.(); }
    closest() { return this; }
  }
  const elements = new Proxy({}, { get: (target, key) => target[key] ||= new Element() });
  const sorts = ['name', 'hosts', 'operational'].map(key => { const el = new Element(); el.dataset.groupSort = key; return el; });
  const requests = [], timers = new Map();
  let timerId = 0;
  const storage = new Map(saved ? [['routiva.diagnostics.groups.filters', JSON.stringify(saved)]] : []);
  elements['incident-group'].options = [{ value: 'a', textContent: 'Grupo A' }, { value: 'b', textContent: 'Grupo B' }];
  elements['group-period'].options = [{ value: '1440' }, { value: '10080' }];
  elements['group-status'].options = [{ value: '' }, { value: 'CRITICO' }];
  elements['group-period'].value = '1440';
  const context = {
    document: { getElementById: id => elements[id], createElement: () => new Element(), createDocumentFragment: () => new Element(), querySelectorAll: () => sorts },
    localStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value) },
    setupMultiPicker(kind, select, label, change) { select.addEventListener('change', change); },
    location: { pathname: '/diagnostico/grupos' },
    addEventListener() {}, URLSearchParams, AbortController, setTimeout(fn, ms) { timers.set(++timerId, {fn, ms}); return timerId; }, clearTimeout(id) { timers.delete(id); },
    fetch: async url => { const params = new URL(url, 'http://localhost').searchParams; requests.push(params); return { ok: true, json: async () => respond(params) }; },
  };
  context.window = context;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/availability-view.js'), 'utf8'), context);
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/diagnostics-groups.js'), 'utf8'), context);
  await tick();
  return { elements, requests, sorts, storage, refresh() { const entry = [...timers].find(([, timer]) => timer.ms === 10000); assert.ok(entry, 'refresh scheduled after 10 seconds'); timers.delete(entry[0]); entry[1].fn(); }, rows: () => elements['group-rows'].children[0].children };
}
const row = { id: 'real/id', name: 'São Paulo', hosts: 3, metrics: { healthy: 90, degraded: 5, lost: 5, errors: 2, operational: 90, technical: 95, loss: 5, latency: 2 }, observedMillis: 60000, incidents: 1, severities: { 5: 1 }, status: 'CRITICO' };
const response = (rows = [row]) => ({ rows, number: 1, pages: 3, total: rows.length ? 45 : 0, from: 1, to: 60001, warning: '' });

test('uses server pagination, preserves filters on refresh and resets page on sorting', async () => {
  const ui = await surface(params => ({ ...response(), number: Number(params.get('page')) }));
  assert.equal(ui.rows().length, 1);
  assert.equal(ui.rows()[0].children.length, 8);
  assert.equal(ui.rows()[0].children[0].children[0].href, '/diagnostico/grupos/real%2Fid?minutes=1440');
  assert.equal(ui.rows()[0].children[2].children[0].children[0].children[0].style.width, '90%');
  ui.elements['groups-next'].emit('click'); await tick();
  assert.equal(ui.requests.at(-1).get('page'), '2');
  ui.refresh(); await tick();
  assert.equal(ui.requests.at(-1).get('page'), '2');
  ui.sorts[2].emit('click'); await tick();
  assert.equal(ui.requests.at(-1).get('sort'), 'operational');
  assert.equal(ui.requests.at(-1).get('page'), '1');
  ui.sorts[2].emit('click'); await tick();
  assert.equal(ui.requests.at(-1).get('direction'), 'desc');
  ui.elements['incident-group'].options.forEach(option => option.selected = true);
  ui.elements['incident-group'].emit('change'); await tick();
  assert.equal(ui.requests.at(-1).get('groupId'), 'a,b');
  ui.elements['group-period'].value = '10080'; ui.elements['group-period'].emit('change'); await tick();
  assert.equal(ui.requests.at(-1).get('minutes'), '10080');
});

test('empty results and unavailable metrics do not show fictitious zeros', async () => {
  const empty = await surface(() => ({ ...response([]), pages: 1 }));
  assert.equal(empty.elements['groups-empty'].hidden, false);
  assert.equal(empty.elements['groups-previous'].disabled, true);
  assert.equal(empty.elements['groups-next'].disabled, true);
  assert.match(empty.elements['groups-empty'].textContent, /Nenhum grupo encontrado/);
  const ui = await surface(() => response([{ ...row, metrics: {}, incidents: null, severities: {}, status: 'SEM_DADOS' }]));
  assert.equal(ui.rows()[0].children[2].children[0].children[0].textContent, '—');
  assert.equal(ui.rows()[0].children[6].children[0].textContent, '—');
  assert.equal(ui.rows()[0].children[7].children[0].textContent, 'Sem dados');
});

test('failures expose retry and preserve the last successful rows', async () => {
  let fail = false;
  const ui = await surface(() => { if (fail) throw new Error('Conexão indisponível'); return response(); });
  fail = true; ui.refresh(); await tick();
  assert.equal(ui.rows().length, 1);
  assert.equal(ui.elements['diagnostic-retry'].hidden, false);
  assert.match(ui.elements['diagnostic-status'].textContent, /indisponível/);
  fail = false; ui.elements['diagnostic-retry'].emit('click'); await tick();
  assert.equal(ui.elements['diagnostic-retry'].hidden, true);
});

test('restores groups, status and period before the initial request and persists changes', async () => {
  const ui = await surface(() => response(), { groups: ['a', 'b'], 'group-status': 'CRITICO', 'group-period': '10080' });
  assert.equal(ui.requests[0].get('groupId'), 'a,b');
  assert.equal(ui.requests[0].get('status'), 'CRITICO');
  assert.equal(ui.requests[0].get('minutes'), '10080');
  ui.elements['incident-group'].options[0].selected = false;
  ui.elements['incident-group'].emit('change'); await tick();
  assert.deepEqual(JSON.parse(ui.storage.get('routiva.diagnostics.groups.filters')).groups, ['b']);
});


test('shows successful refresh time and preserves it when a later refresh fails', async () => {
  let failed = false;
  const ui = await surface(() => { if (failed) throw new Error('Falha'); return response(); });
  const updated = ui.elements['groups-updated'];
  assert.match(updated.textContent, /^Última atualização: /);
  assert.equal(updated.dataset.state, 'success');
  const previous = updated.textContent;
  failed = true;
  ui.refresh(); await tick();
  assert.equal(updated.textContent, previous);
  assert.equal(updated.dataset.state, 'error');
  failed = false;
  ui.refresh(); await tick();
  assert.equal(updated.dataset.state, 'success');
});
