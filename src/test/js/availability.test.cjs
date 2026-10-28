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
  const context = {document: {body:new Element(),addEventListener(){},createDocumentFragment:()=>new Element(),getElementById: id => elements[id], querySelector: id => elements[id], querySelectorAll: () => [], createElement: () => new Element(), createElementNS: () => new Element()}, location, history: {replaceState(a,b,url) { location.href = String(url); }}, URL, URLSearchParams, AbortController, Date, Option: Element, AvailabilityChart: { Chart: class { update() {} clear() {} } },
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
  return {page:{number:1,pages:2,total:21,selectedTotal:21,size:20},collectionStatus:'RECEBENDO',groups: [{id: 'g1', name: 'Grupo 1'}], view: {hosts: Array.from({length: 20}, (_, i) => ({id: String(i), name: 'Host ' + i, metrics})), report: {from: now - 300000, to: now, evaluatedAt: now, retainedFrom: now - 172800000, lastSampleAt: now - 1, total: metrics, series: [{from: now - 60000, to: now, metrics}], load: {phase: 'COMPLETE'}}}};
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
