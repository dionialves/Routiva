const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const controls = require('../../main/resources/static/map-element-controls.js');
function fixture(scale = 1, active = 'rotation', nodeScale = 1) {
  const svg = (tag, attributes) => ({tag, attributes, children: [], append(...items) {this.children.push(...items);}});
  const canvas = svg('svg', {}), calls = [];
  controls.draw({canvas, node: {x: 10, y: 20, scale: nodeScale}, box: {left: -100, right: 100, top: -60, bottom: 60},
    scale, svg, active, onAction: action => calls.push(action),
    onStart: action => calls.push('start:' + action), onAdjust: (...args) => calls.push(args)});
  return {canvas, calls, handles: canvas.children.slice(1)};
}
const event = () => ({button: 0, stopPropagation() {}, preventDefault() {}});
test('four uniform vector controls sit exactly on the selection border at every zoom', () => {
  for (const scale of [.1, 1, 3]) {
    const {canvas, handles} = fixture(scale);
    assert.equal(canvas.children[0].attributes['vector-effect'], 'non-scaling-stroke');
    assert.equal(handles.length, 4);
    const positions = [[10, -40], [110, -40], [110, 20], [110, 80]];
    handles.forEach((handle, i) => {
      assert.equal(handle.attributes.transform, `translate(${positions[i][0]} ${positions[i][1]}) scale(${1 / scale})`);
      assert.equal(handle.children[0].attributes.r, 12);
      assert.equal(handle.children[1].attributes.width, 14);
      assert.ok(handle.children.every(child => child.tag !== 'text'));
    });
  }
});
test('mirror and restore respond once to clicks and keyboard without starting a drag', () => {
  const {handles, calls} = fixture();
  handles[2].onpointerdown(event()); assert.equal(calls.length, 0);
  handles[2].onclick(event());
  handles[3].onkeydown({...event(), key: 'Enter'});
  assert.deepEqual(calls, ['flipX', 'reset']);
  handles[0].onpointerdown(event());
  handles[1].onkeydown({...event(), key: 'ArrowUp', shiftKey: true});
  assert.deepEqual(calls.slice(2), ['start:rotation', ['scale', 1, true]]);
});
test('restore returns to fresh equipment size and orientation while preserving current position and bindings', () => {
  const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/maps.js'), 'utf8');
  const node = {x: 25, y: 40, scale: 1, rotation: 90, flipX: true, ports: [{id: 'p'}], hostId: 'h'};
  const context = vm.createContext({node, checkpoint() {}, draw() {}, $: () => ({querySelector() {}})});
  vm.runInContext(source.slice(source.indexOf('function ensureElementOrigin('), source.indexOf('function drawTransformHandles(')), context);
  context.ensureElementOrigin(node);
  Object.assign(node, {x: 500, y: 600, scale: 2});
  context.applyElementAction(node, 'reset');
  assert.equal(node.x, 500); assert.equal(node.y, 600); assert.equal(node.scale, 1);
  assert.equal(node.rotation, 0); assert.equal(node.flipX, false);
  assert.equal(node.ports[0].id, 'p'); assert.equal(node.hostId, 'h');
  node.x = 800; node.origin.scale = 2; node.scale = 3;
  context.applyElementAction(node, 'reset'); assert.equal(node.x, 800); assert.equal(node.scale, 1);
  context.applyElementAction(node, 'flipX'); assert.equal(node.flipX, true);
  context.applyElementAction(node, 'flipX'); assert.equal(node.flipX, false);
});

test('resize badge displays the node scale above the element only during resizing', () => {
  for (const zoom of [.1, 1, 3]) {
    for (const nodeScale of [.25, 1, 1.37, 3]) {
      const {canvas} = fixture(zoom, 'scale', nodeScale);
      const badge = canvas.children.at(-1);
      assert.equal(badge.attributes.class, 'map-scale-indicator');
      assert.equal(badge.attributes['pointer-events'], 'none');
      assert.equal(badge.attributes.transform, `translate(10 ${-40 - 30 * (1 / zoom)}) scale(${1 / zoom})`);
      assert.equal(badge.children[1].textContent, Math.round(nodeScale * 100) + '%');
    }
    for (const active of [null, 'rotation']) {
      assert.ok(!fixture(zoom, active).canvas.children.some(child => child.attributes.class === 'map-scale-indicator'));
    }
  }
});
