const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const tick = () => new Promise(resolve => setImmediate(resolve));

async function surface(respond, options = {}) {
  class Element {
    constructor() { this.children = []; this.listeners = {}; this.dataset = {}; this.attributes = {}; this.value = ''; this.hidden = true; this.options = []; this.style = { setProperty() {} }; }
    append(...nodes) { for (const node of nodes) this.children.push(...(node.fragment ? node.children : [node])); }
    replaceChildren(...nodes) { this.children = nodes; this.options = nodes; }
    setAttribute(k, v) { this.attributes[k] = v; }
    addEventListener(k, fn) { this.listeners[k] = fn; }
    removeEventListener(k, fn) { if (this.listeners[k] === fn) delete this.listeners[k]; }
    emit(k) { this.listeners[k]?.({ preventDefault() {} }); }
    focus() {}
    reset() {}
    showModal() { this.open = true; }
    close() { this.open = false; this.emit("close"); }
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
  const timers = new Map(), intervals = [], requests = [], events = {}, locations = [], transports = [];
  const main = new Element(); main.dataset.timeZone = 'America/Sao_Paulo'; main.dataset.canUpdate = options.admin ? 'true' : 'false'; main.content = 'csrf';
  const context = {
    document: { getElementById: id => elements[id], createElement: () => new Element(), createElementNS: () => new Element(), createTextNode: text => ({ textContent: text }), createDocumentFragment: () => Object.assign(new Element(), { fragment: true }), querySelector: () => main, addEventListener() {}, removeEventListener() {}, querySelectorAll: selector => selector === '[data-opened-at]' ? [] : selector === '[data-incident-severity]' ? severityOptions : sorts },
    localStorage: { getItem: key => storage.get(key) || null, setItem: (key, value) => storage.set(key, value) },
    location: { search: options.search ?? '?groupId=g&page=2' }, history: { replaceState(state, title, url) { locations.push(url); } },
    FormData: class { constructor() { return fields; } }, URLSearchParams, AbortController, Intl, Date: options.Date || Date,
    setTimeout(fn, ms) { const id = timers.size + 1; timers.set(id, { fn, ms }); return id; },
    clearTimeout(id) { timers.delete(id); }, setInterval(fn) { intervals.push(fn); return intervals.length; }, clearInterval() {},
    addEventListener(key, fn) { events[key] = fn; },
    fetch: async (url, init) => { if (options.api && !url.includes('grouped-data')) return options.api(url, init); const params = init?.method === 'POST' ? new URLSearchParams(JSON.parse(init.body)) : new URL(url, 'http://localhost').searchParams; transports.push({ url, init }); requests.push(params); return respond(params); },
  };
  context.window = context;
  vm.runInNewContext(require('node:fs').readFileSync('src/main/resources/static/host-menu.js', 'utf8'), context);
  vm.runInNewContext(fs.readFileSync(require('node:path').join(__dirname, '../../main/resources/static/multi-picker.js'), 'utf8'), context);
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/incidents.js', 'utf8'), context);
  await tick();
  return { elements, fields, requests, sorts, events, timers, intervals, severityOptions, storage, locations, transports, rows: () => elements['incident-rows'].children[0].children };
}
const row = { eventId: '1', name: '<script>original trigger</script>', severity: 4, severityLabel: 'Alta', timestamp: Date.now() - 87 * 60000, hosts: [{ name: 'Router', ip: '10.0.0.1' }] };
function response(params) { return { ok: true, json: async () => ({ rows: [row], total: 32, page: Number(params.get('page')), pages: 4, size: 10, updatedAt: Date.now(), warning: '' }) }; }

test('renders eight columns safely and calculates durations locally', async () => {
  const ui = await surface(response);
  const cells = ui.rows().find(node => node.dataset.severity != null).children;
  assert.equal(cells.length, 8);
  assert.equal(cells[7].textContent, "—");
  assert.equal(cells[2].children[0].children[0].textContent, 'Router');
  assert.equal(cells[2].children[0].children[1].children[0].href, 'winbox://10.0.0.1');
  assert.equal(cells[3].children[0].textContent, '10.0.0.1');
  assert.equal(cells[6].textContent, 'Não');
  assert.equal(cells[4].children[0].textContent, row.name);
  assert.equal(cells[4].children[0].title, row.name);
  assert.equal(cells[5].children[0].children[1].textContent, '1 h 27 min');
  assert.equal(ui.elements['incident-total'].textContent, '32 encontrados');
  assert.equal(ui.requests[0].get('page'), null);
  ui.intervals[0](); assert.equal(ui.requests.length, 1);
});

test('refresh retains applied filters and submit applies current filters', async () => {
  const ui = await surface(response);
  ui.fields.set('q', 'unsubmitted text');
  [...ui.timers.values()].find(timer => timer.ms === 60000).fn(); await tick();
  assert.equal(ui.requests.at(-1).get('page'), null);
  assert.equal(ui.requests.at(-1).get('q'), null);
  ui.elements['incident-filters'].emit('submit'); await tick();
  assert.equal(ui.requests.at(-1).get('q'), null);
  assert.equal(ui.requests.at(-1).get('page'), null);

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
  assert.equal(partial.rows()[0].className, 'incident-period');
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
  assert.equal(ui.requests.at(-1).get('page'), null);
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

test('shows acknowledged incidents in the rightmost column', async () => {
  const ui = await surface(params => ({ ok: true, json: async () => ({ rows: [{ ...row, acknowledged: true }], total: 1, page: 1, pages: 1, updatedAt: Date.now(), warning: '' }) }));
  const cells = ui.rows().find(node => node.dataset.severity != null).children;
  assert.equal(cells[6].children[0].textContent, 'Sim');
  assert.equal(cells[6].children[0].className, 'incident-acknowledged');
  assert.equal(cells[6].title, 'Incidente reconhecido');
});

function fixedClock(iso) {
  let now = new Date(iso).getTime();
  class Clock extends Date { static now() { return now; } }
  return { Date: Clock, advance: value => { now = new Date(value).getTime(); } };
}
function snapshotResponse(rows) {
  return { ok: true, json: async () => ({ rows, total: rows.length, updatedAt: Date.now(), warning: '' }) };
}
function headers(ui) { return ui.rows().filter(node => node.className === 'incident-period'); }
function incidents(ui) { return ui.rows().flatMap(node => node.fragment ? node.children : [node]).filter(node => node.dataset.severity != null); }

test('exclusive calendar groups include all filtered incidents with independent local pagination', async () => {
  const clock = fixedClock('2026-01-01T15:00:00Z');
  const rows = [
    ...Array.from({ length: 86 }, (_, i) => ({ ...row, eventId: `today${i}`, timestamp: Date.parse('2026-01-01T04:00:00Z') + i })),
    ...Array.from({ length: 63 }, (_, i) => ({ ...row, eventId: `yesterday${i}`, timestamp: Date.parse('2025-12-31T04:00:00Z') + i })),
    { ...row, eventId: 'week', timestamp: Date.parse('2025-12-25T03:00:00Z') },
    { ...row, eventId: 'older', timestamp: Date.parse('2025-12-25T02:59:59Z') },
  ];
  const ui = await surface(() => snapshotResponse(rows), clock);
  assert.deepEqual(headers(ui).map(h => h.children[0].children[0].children[2].textContent),
    ['86 incidentes', '63 incidentes', '1 incidente', '1 incidente']);
  assert.equal(incidents(ui).length, 101);
  assert.equal(headers(ui)[3].children[0].children[0].attributes['aria-expanded'], 'false');
  const footers = ui.rows().flatMap(node => node.fragment ? node.children : [node]).filter(node => node.className === 'incident-period-pagination');
  footers[0].children[0].children[0].children[2].emit('click');
  assert.equal(incidents(ui).length, 87);
  assert.equal(ui.requests.length, 1);
  headers(ui)[3].children[0].children[0].emit('click');
  assert.equal(incidents(ui).length, 88);
  [...ui.timers.values()].find(timer => timer.ms === 60000).fn(); await tick();
  assert.equal(incidents(ui).length, 88);
  assert.equal(headers(ui)[3].children[0].children[0].attributes['aria-expanded'], 'true');
});

test('regroups locally at midnight across year boundary without querying Zabbix', async () => {
  const clock = fixedClock('2026-01-01T02:59:59Z');
  const ui = await surface(() => snapshotResponse([{ ...row, timestamp: Date.parse('2025-12-31T15:00:00Z') }]), clock);
  assert.equal(headers(ui)[0].dataset.period, 'today');
  clock.advance('2026-01-01T03:00:00Z'); ui.intervals[0]();
  assert.equal(headers(ui)[0].dataset.period, 'yesterday');
  assert.equal(ui.requests.length, 1);
});

test('calendar boundaries respect the configured timezone at a daylight saving transition', async () => {
  const clock = fixedClock('2018-11-05T02:30:00Z');
  const ui = await surface(() => snapshotResponse([
    { ...row, eventId: 'today', timestamp: Date.parse('2018-11-05T02:00:00Z') },
    { ...row, eventId: 'yesterday', timestamp: Date.parse('2018-11-04T03:00:00Z') },
    { ...row, eventId: 'week', timestamp: Date.parse('2018-11-04T02:59:59Z') },
  ]), clock);
  assert.deepEqual(headers(ui).map(h => h.dataset.period), ['today', 'yesterday', 'week']);
});

test('incident name picker supports multiple exact names and updates counts locally', async () => {
  const rows = [row, { ...row, eventId: '2', name: 'Link indisponível' },
    { ...row, eventId: '3', name: 'Latência alta' }];
  const ui = await surface(() => ({ ok: true, json: async () => ({ rows, total: 3, updatedAt: Date.now(), warning: '' }) }));
  const options = ui.elements['incident-name-options'].children;
  options[1].children[0].checked = true;
  options[1].children[0].emit('change');
  options[2].children[0].checked = true;
  options[2].children[0].emit('change');
  assert.equal(ui.elements['incident-name-summary'].textContent, '2 incidentes selecionados');
  assert.equal(ui.elements['incident-total'].textContent, '2 encontrados');
  assert.equal(ui.rows().filter(node => node.dataset.severity != null).length, 2);
  assert.equal(ui.requests.length, 1);
  const saved = JSON.parse(ui.storage.get('routiva.incidents.filters'));
  assert.equal(JSON.parse(saved.incidentNames).length, 2);
});

test('restores incident names with commas and preserves selection through refresh', async () => {
  const name = 'Perda alta, enlace São Paulo';
  const ui = await surface(() => ({ ok: true, json: async () => ({ rows: [{ ...row, name }, row], total: 2, updatedAt: Date.now(), warning: '' }) }),
    { search: '', saved: { incidentNames: JSON.stringify([name]) } });
  assert.equal(ui.elements['incident-name-summary'].textContent, name);
  assert.equal(ui.elements['incident-total'].textContent, '1 encontrados');
  [...ui.timers.values()].find(timer => timer.ms === 60000).fn(); await tick();
  assert.equal(ui.elements['incident-name-summary'].textContent, name);
  assert.equal(ui.elements['incident-total'].textContent, '1 encontrados');
});

const details = { eventId: '1', name: 'Link', severity: 4, acknowledged: false, closed: false,
  closing: false, writable: true, manualClose: true,
  history: [{ timestamp: Date.now(), user: 'Operador', action: 6, message: '<script>mensagem</script>' }] };

test('incident link opens modal with safely rendered history and date remains plain text', async () => {
  const ui = await surface(() => ({ ok: true, json: async () => ({ rows: [{ ...row, hasMessages: true, messageCount: 3 }], total: 1, updatedAt: Date.now(), warning: '' }) }), {
    admin: true, api: async () => ({ ok: true, json: async () => details }),
  });
  const cells = ui.rows().find(node => node.dataset.severity != null).children;
  assert.equal(cells[4].children.length, 1);
  assert.equal(cells[7].children[0].children[1].textContent, "3");
  assert.equal(cells[7].children[0].attributes["aria-label"], "Ver 3 mensagens do incidente");
  assert.equal(cells[0].children.length, 0);
  assert.equal(cells[4].children[0].className, 'incident-description incident-link');
  cells[4].children[0].emit('click'); await tick();
  assert.equal(ui.elements['incident-dialog'].open, true);
  assert.equal(ui.elements['incident-history'].children[0].children[3].children[0].textContent, '<script>mensagem</script>');
  assert.equal(ui.elements['incident-update-save'].disabled, true);
});

test('modal disables manual close and severity without Zabbix write permissions', async () => {
  const ui = await surface(response, { admin: true,
    api: async () => ({ ok: true, json: async () => ({ ...details, writable: false }) }),
  });
  ui.rows().find(node => node.dataset.severity != null).children[4].children[0].emit('click'); await tick();
  assert.equal(ui.elements['incident-close'].disabled, true);
  assert.equal(ui.elements['incident-new-severity'].disabled, true);
  assert.equal(ui.elements['incident-update-save'].disabled, true);
});

test('uncertain update preserves message and prevents duplicate submission', async () => {
  let posts = 0;
  const ui = await surface(response, { admin: true, api: async (url, init) => {
    if (init.method === 'POST') { posts++; return { ok: false, status: 502 }; }
    return { ok: true, json: async () => details };
  } });
  ui.rows().find(node => node.dataset.severity != null).children[4].children[0].emit('click'); await tick();
  ui.elements['incident-message'].value = 'Investigando';
  await ui.elements['incident-update-form'].listeners.submit({ preventDefault() {} });
  assert.equal(posts, 1);
  assert.equal(ui.elements['incident-message'].value, 'Investigando');
  assert.equal(ui.elements['incident-update-save'].disabled, true);
  await ui.elements['incident-update-form'].listeners.submit({ preventDefault() {} });
  assert.equal(posts, 1);
});


test('large saved selections use a short POST URL and never copy incident names into headers', async () => {
  const names = Array.from({ length: 300 }, (_, i) => `Incidente São Paulo ${i} ${'x'.repeat(100)}`);
  const hosts = Array.from({ length: 800 }, (_, i) => ({ value: String(i + 2), textContent: `Host ${i}`, dataset: { groups: 'g' } }));
  const ui = await surface(response, { search: '', extraHosts: hosts,
    saved: { incidentNames: JSON.stringify(names), hostId: hosts.map(host => host.value).join(',') } });
  const request = ui.transports[0];
  assert.equal(request.url, '/api/incidents/grouped-data');
  assert.equal(request.init.method, 'POST');
  assert.equal(ui.requests[0].has('incidentNames'), false);
  assert.equal(ui.requests[0].get('hostId'), hosts.map(host => host.value).join(','));
  assert.equal(ui.locations[0], '/incidentes');
  assert.ok(ui.locations.every(url => url.length < 1600 && !url.includes('incidentNames')));
  assert.equal(ui.elements['incident-name-summary'].textContent, '300 incidentes selecionados');
});


test('only changed severity is sent and confirmed update closes the modal', async () => {
  const sent = [];
  const ui = await surface(response, { admin: true, api: async (url, init) => {
    if (init.method === 'POST') { sent.push(JSON.parse(init.body)); return { ok: true }; }
    return { ok: true, json: async () => details };
  } });
  ui.rows().find(node => node.dataset.severity != null).children[4].children[0].emit('click'); await tick();
  assert.equal(ui.elements['incident-update-save'].disabled, true);
  ui.elements['incident-new-severity'].value = '2'; ui.elements['incident-new-severity'].emit('change');
  assert.equal(ui.elements['incident-update-save'].disabled, false);
  ui.elements['incident-new-severity'].value = '4'; ui.elements['incident-new-severity'].emit('change');
  assert.equal(ui.elements['incident-update-save'].disabled, true);
  ui.elements['incident-message'].value = 'Observação'; ui.elements['incident-message'].emit('input');
  assert.equal(ui.elements['incident-message-count'].textContent, '10 / 1.800');
  await ui.elements['incident-update-form'].listeners.submit({ preventDefault() {} });
  assert.equal(sent.length, 1);
  assert.equal(sent[0].severity, null);
  assert.equal(ui.elements['incident-dialog'].open, false);
});

test('history message expands without changing the original content and recognized state is checked', async () => {
  const message = 'Linha longa '.repeat(200) + '\nOutra linha';
  const ui = await surface(response, { admin: true, api: async () => ({ ok: true, json: async () => ({
    ...details, acknowledged: true, history: [{ ...details.history[0], message }],
  }) }) });
  ui.rows().find(node => node.dataset.severity != null).children[4].children[0].emit('click'); await tick();
  assert.equal(ui.elements['incident-recognize'].checked, true);
  assert.equal(ui.elements['incident-recognize'].disabled, true);
  assert.equal(ui.elements['incident-history-count'].textContent, '1 registro');
  const preview = ui.elements['incident-history'].children[0].children[3].children[0];
  assert.equal(preview.attributes['aria-expanded'], 'false'); preview.emit('click');
  assert.equal(preview.attributes['aria-expanded'], 'true'); assert.equal(preview.textContent, message);
  preview.emit('click'); assert.equal(preview.attributes['aria-expanded'], 'false');
});


test('Zabbix manual close restriction disables closing while keeping comments available', async () => {
  const sent = [];
  const ui = await surface(response, { admin: true, api: async (url, init) => {
    if (init.method === 'POST') { sent.push(JSON.parse(init.body)); return { ok: true }; }
    return { ok: true, json: async () => ({ ...details, manualClose: false }) };
  } });
  ui.rows().find(node => node.dataset.severity != null).children[4].children[0].emit('click'); await tick();
  assert.equal(ui.elements['incident-close'].disabled, true);
  assert.equal(ui.elements['incident-close'].checked, false);
  assert.match(ui.elements['incident-close-help'].textContent, /não permite o fechamento manual/);
  assert.equal(ui.elements['incident-new-severity'].disabled, false);
  assert.equal(ui.elements['incident-update-fields'].disabled, false);
  ui.elements['incident-message'].value = 'Investigando';
  ui.elements['incident-close'].checked = true;
  await ui.elements['incident-update-form'].listeners.submit({ preventDefault() {} });
  assert.equal(sent[0].close, false);
});

test('closed and closing events explicitly disable the closing checkbox with a reason', async () => {
  for (const state of ['closed', 'closing']) {
    const ui = await surface(response, { admin: true,
      api: async () => ({ ok: true, json: async () => ({ ...details, [state]: true }) }),
    });
    ui.rows().find(node => node.dataset.severity != null).children[4].children[0].emit('click'); await tick();
    assert.equal(ui.elements['incident-close'].disabled, true);
    assert.equal(ui.elements['incident-close'].checked, false);
    assert.equal(ui.elements['incident-update-fields'].disabled, true);
    assert.equal(ui.elements['incident-update-save'].disabled, true);
    assert.match(ui.elements['incident-close-help'].textContent, state === 'closed' ? /já está encerrado/ : /já foi solicitado/);
  }
});


test('map event links show only the exact ID, ignore saved filters and survive refresh', async () => {
  const ui = await surface(() => ({ ok: true, json: async () => ({
    rows: [row, { ...row, eventId: '2' }], total: 2, updatedAt: Date.now(), warning: ''
  }) }), { search: '?eventId=2', saved: { groupId: 'g', severity: 'none', incidentNames: '["other"]' } });
  assert.equal(ui.requests[0].get('groupId'), '');
  assert.equal(ui.requests[0].get('severity'), '');
  assert.equal(ui.rows().filter(node => node.dataset.severity != null).length, 1);
  assert.equal(ui.elements['incident-total'].textContent, '1 encontrados');
  assert.ok(ui.locations.every(url => url === '/incidentes?eventId=2'));
  [...ui.timers.values()].find(timer => timer.ms === 60000).fn(); await tick();
  assert.equal(ui.rows().filter(node => node.dataset.severity != null).length, 1);
  assert.equal(JSON.parse(ui.storage.get('routiva.incidents.filters')).severity, 'none');
});

test('a missing linked event shows an empty result instead of unrelated incidents', async () => {
  const ui = await surface(response, { search: '?eventId=missing' });
  assert.equal(ui.elements['incident-empty'].hidden, false);
  assert.equal(ui.elements['incident-total'].textContent, '0 encontrados');
});
