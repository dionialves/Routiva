const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/maps.js'), 'utf8');

function setup() {
  const canvas = { setPointerCapture() {}, getScreenCTM: () => ({ inverse() {} }) };
  const context = vm.createContext({ canvas, checkpoints: 0, opened: 0 });
  vm.runInContext(`
    const $ = () => canvas;
    let editing = true, tool = 'select', selected, selectedLink, drag, marquee;
    const selection = new Set();
    const current = {layout:{nodes:[{id:'a',x:0,y:0},{id:'b',x:40,y:20},{id:'c',x:200,y:200}]}};
    const camera = {scale:1};
    function draw() {}
    function checkpoint() { checkpoints++; }
    function openElement() { opened++; }
    class DOMPoint {
      constructor(x,y) { this.x=x; this.y=y; }
      matrixTransform() { return this; }
    }
  `, context);
  vm.runInContext(source.slice(source.indexOf('function alignDraggedNode('), source.indexOf('\nfunction draw()')), context);
  vm.runInContext(source.slice(source.indexOf('function choose('), source.indexOf('\nconst viewport')), context);
  vm.runInContext(source.slice(source.indexOf("$('canvas').onpointerdown ="), source.indexOf('\nasync function refresh')), context);
  return { canvas, run: code => vm.runInContext(code, context) };
}

test('modifier selection toggles elements without opening their editor', () => {
  const { run } = setup();
  run("choose(current.layout.nodes[0], true); choose(current.layout.nodes[1], true);");
  assert.equal(run('selection.size'), 2);
  run('choose(current.layout.nodes[0], true)');
  assert.equal(run("selection.has('a')"), false);
  assert.equal(run("selection.has('b')"), true);
  assert.equal(run('opened'), 0);
});

test('selection rectangle works backwards and disappears on release', () => {
  const { canvas, run } = setup();
  canvas.onpointerdown({button:0, target:canvas, pointerId:1, clientX:60, clientY:40});
  canvas.onpointermove({pointerId:1, clientX:-10, clientY:-10});
  assert.equal(run('selection.size'), 2);
  canvas.onpointerup({pointerId:1});
  assert.equal(run('marquee'), null);
  assert.equal(run('selection.size'), 2);
  assert.equal(run('checkpoints'), 0);
});

test('group drag preserves spacing, uses one undo step and clears guides on release', () => {
  const { canvas, run } = setup();
  run(`
    selection.add('a'); selection.add('b');
    drag = {node:current.layout.nodes[0], pointer:1, x:0, y:0, dx:0, dy:0, moved:false,
      positions:current.layout.nodes.slice(0,2).map(node => ({node,x:node.x,y:node.y}))};
  `);
  canvas.onpointermove({pointerId:1, clientX:50, clientY:70});
  canvas.onpointermove({pointerId:1, clientX:80, clientY:90});
  assert.equal(run('current.layout.nodes[1].x - current.layout.nodes[0].x'), 40);
  assert.equal(run('current.layout.nodes[1].y - current.layout.nodes[0].y'), 20);
  assert.equal(run('current.layout.nodes[2].x'), 200);
  assert.equal(run('checkpoints'), 1);
  canvas.onpointerup({pointerId:1});
  assert.equal(run('drag'), null);
  assert.equal(run('selection.size'), 2);
  assert.equal(run('opened'), 0);
});
