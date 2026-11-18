const {test} = require('node:test');
const assert = require('node:assert/strict');
const traffic = require('../../main/resources/static/map-traffic.js');
test('directional intensity uses real capacity and includes boundary values', () => {
  assert.deepEqual([0, 1, 5, 5.1, 30, 30.1, 70, 70.1, 100, 120].map(n => traffic.count(n, 100)),
    [0, 1, 1, 2, 2, 3, 3, 4, 4, 4]);
  assert.equal(traffic.count(50, null), 0);
});
test('missing, reset and invalid rates stay distinct from zero', () => {
  for (const n of [null, undefined, '', 'NaN', '-1', Infinity]) assert.equal(traffic.number(n), null);
  assert.equal(traffic.rate(null), 'Sem dados');
  assert.equal(traffic.rate('0'), '0 Mbps');
  assert.equal(traffic.rate('1000000000'), '1 Gbps');
});
test('stale samples and API errors cannot animate or imply DOWN', () => {
  const endpoint = {measuredAt: 10000, up: true, txBps: '500', rxBps: '0', capacityBps: 1000};
  assert.deepEqual(traffic.state({a: endpoint}, 11000), {down:false, tx:500, rx:0, capacity:1000});
  assert.equal(traffic.state({a: endpoint}, 41000).tx, null);
  assert.equal(traffic.state({a: {...endpoint, up:false, error:'API'}}, 11000).down, false);
  const down = traffic.state({a: endpoint, b:{...endpoint, up:false}}, 11000);
  assert.equal(down.down, true);
  assert.equal(down.tx, null);
});
function element(tag, attrs = {}) {
  return {tag, attrs, children: [], parentNode: null,
    append(child) { child.parentNode = this; this.children.push(child); },
    replaceChildren() { this.children = []; },
    getAttribute(key) { return this.attrs[key]; },
    querySelectorAll() { return []; },
    contains() { return false; },
    classList: {toggle(name, value) { attrs[name] = value; }}};
}
test('SVG particles reuse curved geometry and travel independently in opposite directions', () => {
  const layer = element('g'); layer.parentNode = element('g');
  const d = 'M 0 0 L 0 80 Q 0 100 20 100 L 200 100';
  const path = element('path', {d});
  const a = {up:true, measuredAt:Date.now(), txBps:20, rxBps:80, capacityBps:100};
  traffic.render(element, layer, path, [{x:0,y:0},{x:0,y:100},{x:200,y:100}], [], {a}, true);
  const particles = layer.children.filter(n => n.tag === 'circle');
  assert.equal(particles.length, 6);
  assert.equal(particles.filter(n => n.attrs.fill === '#63E4FD').length, 2);
  assert.equal(particles.filter(n => n.attrs.fill === '#4DE2A7').length, 4);
  assert.ok(particles.every(n => n.children[0].attrs.path === d));
  assert.equal(particles[0].children[0].attrs.keyPoints, '0;1');
  assert.equal(particles[2].children[0].attrs.keyPoints, '1;0');
  traffic.render(element, layer, path, [{x:0,y:0},{x:200,y:0}], [], {a}, true, true);
  assert.equal(layer.children[0].children[0].attrs.keyPoints, '1;0');
  traffic.render(element, layer, path, [{x:0,y:0},{x:200,y:0}], [], {a}, false);
  assert.equal(layer.children.filter(n => n.tag === 'circle').length, 0);
  assert.equal(layer.children[0].children.length, 2);
});

test('a failed collection preserves DOWN and never animates presumed traffic', () => {
  const layer = element('g'); layer.parentNode = element('g');
  layer.trafficContext = {link:{mappedConnectionId:'down-link'}};
  const path = element('path', {d:'M 0 0 L 200 0'});
  const a = {measuredAt:Date.now(), up:false, txBps:80, rxBps:50, capacityBps:100};
  const route = [{x:0,y:0},{x:200,y:0}];
  traffic.render(element, layer, path, route, [], {id:'down-link',a}, true);
  assert.equal(path.attrs['traffic-down'], true);
  traffic.render(element, layer, path, route, [], undefined, true);
  assert.equal(path.attrs['traffic-down'], true);
  assert.equal(layer.children.filter(n => n.tag === 'circle').length, 0);
});
