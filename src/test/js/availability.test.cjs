const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync(require('node:path').join(__dirname, '../../main/resources/static/availability.js'), 'utf8');
function surface(search = '') {
  class Element {
    constructor(text = '', value = '') { this.textContent = text; this.value = value; this.children = []; this.listeners = {}; this.dataset = {}; this.style = {}; this.classList={toggle(){},contains(){return false;}}; }
    append(...items) { this.children.push(...items); }
    add(item) { this.append(item); }
    replaceChildren(...items) { this.children = items; }
    setAttribute() {}
    addEventListener(name, fn) { this.listeners[name] = fn; }
    emit(name) { this.listeners[name]?.({preventDefault() {}}); }
  }
  const elements = new Proxy({}, {get(target, key) { return target[key] ||= new Element(); }});
  const requests = [], timers = new Map(); let serial = 0;
  const location = {search, pathname:'/disponibilidade', href: 'http://localhost/disponibilidade' + search};
  const context = {document: {body:new Element(),addEventListener(){},createDocumentFragment:()=>new Element(),getElementById: id => elements[id], querySelector: id => elements[id], querySelectorAll: () => [], createElement: tagName => Object.assign(new Element(), {tagName}), createElementNS: () => new Element()}, location, history: {replaceState(a,b,url) { location.href = String(url); }}, URL, URLSearchParams, AbortController, Date, Option: Element, AvailabilityChart: { Chart: class { update() {} clear() {} } },
    setTimeout(fn, ms) { timers.set(++serial, {fn, ms}); return serial; }, clearTimeout(id) { timers.delete(id); },
    fetch(url, options) { return new Promise((resolve, reject) => {requests.push({url, resolve, reject, options});}); }
  };
  context.window=context;vm.runInNewContext(fs.readFileSync(require('node:path').join(__dirname,'../../main/resources/static/availability-view.js'),'utf8'),context);
  vm.runInNewContext(source,context);
  return {elements, requests, timers, location, respond(i, body, status = 200) {requests[i].resolve({ok: status === 200, status, json: async () => body});}};
}
const flush = () => new Promise(resolve => setImmediate(resolve));
function data(technical = 99) {
  const now = Date.now(), metrics = {technical, operational: 95, loss: 1, latency: 15};
  return {page:{number:1,pages:2,total:21,selectedTotal:21,size:20},collectionStatus:'RECEBENDO',groups: [{id: 'g1', name: 'Grupo 1'}], view: {hosts: Array.from({length: 20}, (_, i) => ({id: String(i), name: 'Host ' + i, metrics})), report: {from: now - 300000, to: now, evaluatedAt: now, retainedFrom: now - 172800000, lastSampleAt: now - 1, total: metrics, series: [{from: now - 300000, to: now, metrics}], load: {phase: 'COMPLETE'}}}};
}
test('defaults to 48 hours and all hosts; renders cards and paginates without changing totals', async () => {
  const ui = surface(); assert.match(ui.requests[0].url, /minutes=2880&groupId=&page=1/);
  ui.respond(0, data()); await flush();
  assert.equal(ui.elements.technical.textContent, '99,00%');
  assert.equal(ui.elements['availability-hosts'].children[0].children.length, 20);
  ui.elements.next.emit('click'); assert.match(ui.requests[1].url,/page=2/); const next=data();next.page.number=2;next.view.hosts=next.view.hosts.slice(0,1);ui.respond(1,next);await flush();assert.equal(ui.elements['availability-hosts'].children[0].children.length,1);
  assert.equal(ui.elements.technical.textContent, '99,00%');
  assert.ok([...ui.timers.values()].some(t => t.ms === 10000));
});
test('filter changes abort old requests and discard stale responses', async () => {
  const ui = surface(); ui.elements.period.value = '5'; ui.elements.group.value = 'g1'; ui.elements.group.emit('change');
  assert.equal(ui.requests[0].options.signal.aborted, true);
  assert.match(ui.requests[1].url, /minutes=5&groupId=g1/);
  ui.respond(1, data(80)); await flush(); ui.respond(0, data(100)); await flush();
  assert.equal(ui.elements.technical.textContent, '80,00%'); assert.match(ui.location.href, /groupId=g1/);
});
test('failed refresh preserves data and schedules recovery; loading does not invent zero', async () => {
  const ui = surface(); ui.respond(0, {groups: [], view: null}); await flush();
  assert.match(ui.elements['availability-status'].textContent, /Carregando/);
  [...ui.timers.values()].find(t => t.ms === 10000).fn(); ui.respond(1, data()); await flush();
  [...ui.timers.values()].find(t => t.ms === 10000).fn(); ui.respond(2, {}, 500); await flush();
  assert.equal(ui.elements.technical.textContent, '99,00%');
  assert.match(ui.elements['availability-status'].textContent, /últimos dados/);
});
test('search is debounced on the server and preserves filter context', async () => {
  const ui=surface('?minutes=60&groupId=g1');ui.respond(0,data());await flush();
  ui.elements['host-search'].value='roteador';ui.elements['host-search'].emit('input');
  ui.elements['host-search'].value='10.0.0.1';ui.elements['host-search'].emit('input');
  assert.equal(ui.requests.length,1);
  [...ui.timers.values()].find(t=>t.ms===300).fn();
  assert.match(ui.requests[1].url,/minutes=60&groupId=g1&page=1/);
  assert.match(ui.requests[1].url,/q=10.0.0.1/);
});
test('availability times use report period and complementary rounded seconds', async () => {
  const ui = surface(), report = data(99.49);
  report.view.report.from = report.view.report.to - 48 * 3600000;
  report.view.report.total.operational = 97.70;
  report.view.report.series[0].from = report.view.report.from;
  ui.respond(0, report); await flush();
  assert.equal(ui.elements['operational-available'].textContent, '46 h 53 min 46 s');
  assert.equal(ui.elements['operational-unavailable'].textContent, '1 h 6 min 14 s');
  assert.equal(ui.elements['technical-available'].textContent, '47 h 45 min 19 s');
  assert.equal(ui.elements['technical-unavailable'].textContent, '14 min 41 s');
  ui.elements.period.value = '5'; ui.elements.period.emit('change');
  assert.equal(ui.elements['technical-available'].textContent, '—');
  ui.respond(1, data(100)); await flush();
  assert.equal(ui.elements['technical-available'].textContent, '5 min');
  assert.equal(ui.elements['technical-unavailable'].textContent, '0 s');
});
test('missing measurements do not become downtime and zero availability remains valid', async () => {
  const ui = surface(), report = data(null);
  report.view.report.total.operational = null;
  ui.respond(0, report); await flush();
  assert.equal(ui.elements['technical-available'].textContent, 'Sem dados');
  assert.equal(ui.elements['technical-unavailable'].textContent, 'Sem dados');
  assert.equal(ui.elements['operational-available'].textContent, 'Sem dados');
  [...ui.timers.values()].find(t => t.ms === 10000).fn();
  const zero = data(0); zero.view.report.total.operational = 0;
  ui.respond(1, zero); await flush();
  assert.equal(ui.elements['operational-available'].textContent, '0 s');
  assert.equal(ui.elements['operational-unavailable'].textContent, '5 min');
});
test('48-hour selection ignores 47 hours without measurements', async () => {
  const ui = surface(), payload = data(50), r = payload.view.report;
  r.from = r.to - 48 * 3600000;
  r.series = [
    {from:r.from, to:r.to - 3600000, metrics:{technical:null}},
    {from:r.to - 3600000, to:r.to, metrics:r.total}
  ];
  ui.respond(0, payload); await flush();
  assert.equal(ui.elements['technical-available'].textContent, '30 min');
  assert.equal(ui.elements['technical-unavailable'].textContent, '30 min');
});
test('one-hour selection clips older data without discounting covered time', async () => {
  const ui = surface('?minutes=60'), payload = data(75), r = payload.view.report;
  r.from = r.to - 3600000;
  r.series = [{from:r.to - 8 * 3600000, to:r.to, metrics:r.total}];
  ui.respond(0, payload); await flush();
  assert.equal(ui.elements['technical-available'].textContent, '45 min');
  assert.equal(ui.elements['technical-unavailable'].textContent, '15 min');
});
test('internal gaps and collection errors are excluded; lost pings count as covered time', async () => {
  const ui = surface(), payload = data(0), r = payload.view.report;
  r.series = [
    {from:r.from, to:r.to - 120000, metrics:{technical:null, errors:10}},
    {from:r.to - 120000, to:r.to - 60000, metrics:{technical:null}},
    {from:r.to - 60000, to:r.to, metrics:r.total}
  ];
  ui.respond(0, payload); await flush();
  assert.equal(ui.elements['technical-available'].textContent, '0 s');
  assert.equal(ui.elements['technical-unavailable'].textContent, '1 min');
});

test('host names remain plain text without links to removed detail pages', async () => {
  const ui = surface(); ui.respond(0, data()); await flush();
  const row = ui.elements['availability-hosts'].children[0].children[0];
  const name = row.children[0].children[0];
  assert.equal(name.tagName, 'span');
  assert.equal(name.textContent, 'Host 0');
  assert.equal(name.href, undefined);
});

test('minute aggregation shows consolidation and pending backlog without claiming fresh collection', async () => {
  const ui=surface(), payload=data();
  payload.view.report.load.phase='CATCHING_UP';
  payload.view.report.to=Math.floor(Date.now()/60000)*60000;
  ui.respond(0,payload);await flush();
  assert.equal(ui.elements['collection-state'].textContent,'Processando histórico pendente');
  assert.match(ui.elements['availability-status'].textContent,/Dados consolidados até/);
  assert.match(ui.elements['availability-coverage'].textContent,/dados ainda são parciais/);
});

test('normal minute aggregation interval is not treated as an outdated ten-second publication', async () => {
  const ui=surface(), payload=data();
  payload.view.report.evaluatedAt=Date.now()-90000;
  ui.respond(0,payload);await flush();
  assert.equal(ui.elements['collection-state'].textContent,'Coleta recebendo medições');
});
