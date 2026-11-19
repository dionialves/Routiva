const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync(require('node:path').join(__dirname, '../../main/resources/static/maps.js'), 'utf8');
const move = source.slice(source.indexOf("  if (drag.kind === 'transform') {", source.indexOf("$('canvas').onpointermove")), source.indexOf("  if (drag.kind === 'new-bend') {", source.indexOf("$('canvas').onpointermove")));
function setup(action) {
  const context = vm.createContext({drag: {kind: 'transform', action, node: {x: 0, y: 0},
    initial: {rotation: 0, scale: 1, flipX: false}, start: {x: 100, y: 0}, x: 100, y: 0, moved: false},
    canvasPoint: event => ({x: event.clientX, y: event.clientY}), checkpoints: 0,
    checkpoint() {context.checkpoints++;}, draw() {}});
  vm.runInContext(`function move(event) {${move}}`, context);
  return context;
}
test('rotation drag follows pointer, supports snapping and records one undo step', () => {
  const c = setup('rotation');
  c.move({clientX: 0, clientY: 100});
  assert.equal(c.drag.node.rotation, 90);
  c.move({clientX: -100, clientY: 0});
  assert.equal(c.drag.node.rotation, 180);
  assert.equal(c.checkpoints, 1);
  c.move({clientX: 90, clientY: 30, shiftKey: true});
  assert.equal(c.drag.node.rotation, 15);
});
test('resizing remains proportional and bounded', () => {
  const c = setup('scale');
  c.move({clientX: 200, clientY: 0}); assert.equal(c.drag.node.scale, 2);
  c.move({clientX: 900, clientY: 0}); assert.equal(c.drag.node.scale, 3);
  c.move({clientX: 0, clientY: 0}); assert.equal(c.drag.node.scale, .25);
});
test('cancel restores original transforms and redo history', () => {
  const c = setup('rotation');
  Object.assign(c, {past: ['before'], future: [], draw() {}});
  c.drag.future = ['redo']; c.drag.moved = true;
  c.drag.node.rotation = 90;
  vm.runInContext(source.slice(source.indexOf('function cancelTransform()'), source.indexOf('function drawTransformHandles(')), c);
  assert.equal(c.cancelTransform(), true);
  assert.equal(c.drag, null);
  assert.equal(c.past.length, 0);
  assert.equal(c.future[0], 'redo');
});
