const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/maps.js'), 'utf8');

function setup(editing = false, scale = 1) {
  const buttons = {'zoom-in': {}, 'zoom-out': {}};
  const context = vm.createContext({
    camera: {x: 100, y: 200, scale}, current: {}, editing,
    minZoom: 0.1, maxZoom: 3, drag: null, pan: null, marquee: null,
    viewport: {clientHeight: 600, addEventListener(type, handler, options) {
      this[type] = handler;
      assert.equal(options.passive, false);
    }},
    $: id => buttons[id], stopMotion() {}, resizeCanvas() {}, syncZoom() {},
    popSummary: {close() {}}, MapConnectionTooltip: {hide() {}},
    canvasPoint: event => ({x: context.camera.x + event.clientX / context.camera.scale,
      y: context.camera.y + event.clientY / context.camera.scale})
  });
  vm.runInContext(source.slice(source.indexOf('function changeZoom('), source.indexOf('new ResizeObserver(')), context);
  return {context, buttons, wheel(deltaY, deltaMode = 0) {
    let prevented = false;
    context.viewport.wheel({deltaY, deltaMode, clientX: 240, clientY: -180,
      preventDefault() { prevented = true; }});
    assert.equal(prevented, true);
  }};
}

for (const editing of [false, true]) {
  test(`wheel zoom preserves the map point beneath the cursor in ${editing ? 'edit' : 'view'} mode`, () => {
    const {context, wheel} = setup(editing);
    const anchor = {x: 340, y: 20};
    for (const delta of [-100, -100, 100, 100]) {
      wheel(delta);
      assert.ok(Math.abs((anchor.x - context.camera.x) * context.camera.scale - 240) < 1e-9);
      assert.ok(Math.abs((anchor.y - context.camera.y) * context.camera.scale + 180) < 1e-9);
    }
    assert.ok(Math.abs(context.camera.scale - 1) < 1e-9);
  });
}

test('wheel supports line deltas and clamps zoom without shifting at the limit', () => {
  const {context, wheel} = setup();
  wheel(-1, 1);
  assert.ok(context.camera.scale > 1);
  wheel(-10000);
  assert.equal(context.camera.scale, 3);
  const before = {...context.camera};
  wheel(-100);
  assert.deepEqual(context.camera, before);
  wheel(10000);
  assert.equal(context.camera.scale, 0.1);
});

test('wheel does not interfere with active gestures and buttons still zoom around the center', () => {
  const {context, buttons, wheel} = setup(true);
  for (const gesture of ['drag', 'pan', 'marquee']) {
    context[gesture] = {};
    wheel(-100);
    assert.equal(context.camera.scale, 1);
    context[gesture] = null;
  }
  buttons['zoom-in'].onclick();
  assert.deepEqual(context.camera, {x: 100, y: 200, scale: 1.1});
});
