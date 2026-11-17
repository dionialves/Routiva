const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/maps.js'), 'utf8');

function setup() {
  const canvas = { setPointerCapture() {}, getScreenCTM: () => ({ inverse() {} }) };
  const context = vm.createContext({ canvas, checkpoints: 0, opened: 0, MapLinks: require('../../main/resources/static/map-links.js') });
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

test('dragging a connection corner changes only that waypoint and records one undo step', () => {
  const {canvas, run} = setup();
  run(`
    function canvasPoint(event) { return {x:event.clientX, y:event.clientY}; }
    function linkRoute(link) { return [{x:-100,y:0}, ...link.points, {x:300,y:0}]; }
    const link = {points:[{x:0,y:80},{x:200,y:80}]};
    selectedLink = link;
    drag = {kind:'bend', link, index:0, pointer:4, moved:false};
  `);
  canvas.onpointermove({pointerId:4, clientX:20, clientY:100});
  canvas.onpointermove({pointerId:4, clientX:30, clientY:110});
  assert.equal(run('link.points[0].x'), 30);
  assert.equal(run('link.points[0].y'), 110);
  assert.equal(run('link.points[1].x'), 200);
  assert.equal(run('checkpoints'), 1);
  canvas.onpointerup({pointerId:4});
  assert.equal(run('drag'), null);
  assert.equal(run('opened'), 0);
  assert.equal(run('selectedLink === link'), true);
});

test('dragging a line inserts one corner on the pressed segment with a single undo step', () => {
  const {canvas,run} = setup();
  run(`
    function canvasPoint(event) {return {x:event.clientX,y:event.clientY};}
    function linkRoute(link) {return [{x:0,y:0}, ...link.points, {x:200,y:0}];}
    const link = {style:'corners',points:[]};
    drag = {kind:'new-bend',link,pointer:9,x:100,y:0,start:{x:100,y:0},moved:false};
  `);
  canvas.onpointermove({pointerId:9,clientX:100,clientY:2});
  assert.equal(run('link.points.length'),0);
  assert.equal(run('checkpoints'),0);
  canvas.onpointermove({pointerId:9,clientX:100,clientY:50});
  canvas.onpointermove({pointerId:9,clientX:110,clientY:70});
  assert.equal(run('link.points.length'),1);
  assert.equal(run('link.points[0].x'),110);
  assert.equal(run('link.points[0].y'),70);
  assert.equal(run('checkpoints'),1);
  canvas.onpointerup({pointerId:9});
  assert.equal(run('drag'),null);
  assert.equal(run('opened'),0);
});

test('clicking a line without dragging never creates a corner', () => {
  const {canvas,run} = setup();
  run(`const link={points:[]};drag={kind:'new-bend',link,pointer:9,moved:false};`);
  canvas.onpointerup({pointerId:9});
  assert.equal(run('link.points.length'),0);
  assert.equal(run('checkpoints'),0);
});
