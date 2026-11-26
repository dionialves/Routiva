const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const context = vm.createContext({MapTraffic:require('../../main/resources/static/map-traffic.js'), module:{exports:{}}, Date, setTimeout, clearTimeout});
vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/map-connection-tooltip.js'), 'utf8'), context);
const {details} = context.module.exports;
const now = 100000;
const definition = {hostA:'1',hostB:'2',interfaceA:'ether1',interfaceB:'sfp1'};
const hosts = [{id:'1',name:'A <script>'},{id:'2',name:'B'}];
const sample = overrides => ({a:{name:'ether1',up:true,measuredAt:now,capacityBps:'1000000000', ...overrides}, b:{name:'sfp1',up:true,measuredAt:now}});
test('operational endpoints remain active when traffic counters are missing', () => {
  const data = details(sample(), definition, hosts, false, now);
  assert.equal(data.status, 'Ativa');
  assert.equal(data.traffic.tx, null);
  assert.equal(data.traffic.capacity, 1e9);
  assert.equal(data.endpoints[0].name, 'A <script>');
});
test('only a fresh operational DOWN reading marks the connection inactive', () => {
  assert.equal(details(sample({up:false}), definition, hosts, false, now).status, 'Inativa');
  assert.equal(details(sample({up:false, measuredAt:now-31000}), definition, hosts, false, now).status, 'Indisponível');
  assert.equal(details(sample({up:false, error:'Falha API'}), definition, hosts, false, now).status, 'Indisponível');
  assert.equal(details(sample({up:false, measuredAt:now+1000}), definition, hosts, false, now).status, 'Indisponível');
});
test('missing readings preserve endpoint definitions and report missing timestamp', () => {
  const data = details(null, definition, hosts, true, now);
  assert.equal(data.status, 'Indisponível');
  assert.equal(data.endpoints[1].interface, 'sfp1');
  assert.equal(data.measuredAt, null);
  assert.match(data.note, /Consulta indisponível/);
});
test('valid zero traffic and actual collection timestamps are retained', () => {
  const data = details(sample({txBps:'0',rxBps:'450000000'}), definition, hosts, false, now);
  assert.equal(data.traffic.tx, 0);
  assert.equal(data.traffic.rx, 450e6);
  assert.equal(data.measuredAt, now);
});

test('tooltip clamps position to the viewport and renders host text safely', () => {
  class Node {
    constructor(tag) { this.tag = tag; this.children = []; this.attributes = {}; this.style = {}; }
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; }
    setAttribute(key, value) { this.attributes[key] = value; }
    removeAttribute(key) { delete this.attributes[key]; }
    getBoundingClientRect() { return {width:380, height:480}; }
  }
  const body = new Node('body');
  const handlers = {};
  context.document = {body, createElement:tag => new Node(tag), createElementNS:(_, tag) => new Node(tag),
    addEventListener:(name, handler) => { handlers[name] = handler; }};
  context.window = {addEventListener(){}};
  context.innerWidth = 400; context.innerHeight = 600;
  const source = new Node('path');
  const data = details(sample(), definition, hosts, false, now);
  context.module.exports.show('connection', source, {x:395,y:595}, data);
  const panel = body.children[0];
  assert.equal(panel.style.left, '12px');
  assert.equal(panel.style.top, '99px');
  assert.equal(source.attributes['aria-describedby'], 'map-connection-tooltip');
  const findText = node => [node.textContent, ...node.children.flatMap(findText)];
  assert.ok(findText(panel).includes('A <script>'));
  assert.equal(panel.children[2].children[1].children[0].children[1].textContent, 'Sem dados');
  assert.equal(panel.children[2].children[1].children[1].children[0].style.width, '0%');
  context.module.exports.refresh('connection', details(sample({txBps:'45120000',rxBps:'146500000'}), definition, hosts, false, now));
  assert.equal(panel.children[2].children[1].children[0].children[2].textContent, '4,51%');
  assert.ok(Math.abs(parseFloat(panel.children[2].children[2].children[1].children[0].style.width) - 14.65) < 0.001);
  context.innerWidth = 1000;
  context.module.exports.show('connection', source, {x:500,y:595}, data);
  assert.equal(panel.attributes['data-pointer'], 'bottom');
  assert.equal(panel.style['--pointer-x'], '190px');
  handlers.keydown({key:'Escape'});
  const callbacks = new Map();
  let counter = 0;
  context.setTimeout = (fn, delay) => {callbacks.set(++counter, {fn, delay}); return counter;};
  context.clearTimeout = id => callbacks.delete(id);
  const tooltip = context.module.exports;
  tooltip.scheduleShow('connection', source, {x:400,y:595}, data);
  assert.equal(panel.hidden, true);
  assert.equal(callbacks.get(counter).delay, 1000);
  const timer = counter;
  tooltip.scheduleShow('connection', source, {x:500,y:595}, data);
  assert.equal(counter, timer);
  tooltip.hide();
  assert.equal(callbacks.size, 0);
  tooltip.scheduleShow('connection', source, {x:400,y:595}, data);
  tooltip.scheduleShow('other', source, {x:500,y:595}, data);
  assert.equal(callbacks.size, 1);
  callbacks.get(counter).fn();
  assert.equal(panel.hidden, false);
  assert.equal(panel.style['--pointer-x'], '190px');
  tooltip.hide();
  assert.equal(panel.hidden, true);
  assert.equal(source.attributes['aria-describedby'], undefined);
});

test('aggregate PON traffic remains available when an individual destination is down', () => {
  const reading = sample({txBps: '200000000'});
  reading.b.up = false;
  const data = details(reading, definition, hosts, false, now, true);
  assert.equal(data.status, 'Inativa');
  assert.equal(data.aggregatePon, true);
  assert.equal(data.traffic.tx, 200e6);
});


test('tooltip uses the same capacity source as map traffic, without fallback', () => {
  const link = {from:'a',to:'b',appearance:'dashed-pulses',connection:definition};
  const port = {name:'ether1',connector:'electrical',capacity:'100m'};
  const nodes = [{id:'a',hostId:'1',ports:[port]}];
  const capacity = reading => details(reading, definition, hosts, false, now, false, link, nodes).traffic.capacity;
  assert.equal(capacity(sample()), 1e9);
  assert.equal(capacity(sample({capacityBps:null})), null);
  port.connector = 'wireless';
  link.appearance = 'solid-pulses';
  assert.equal(capacity(sample()), 100e6);
  assert.equal(capacity(sample({capacityBps:null})), 100e6);
  port.capacity = 'unknown';
  assert.equal(capacity(sample()), null);
});
