const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../../main/resources/static/maps.js'), 'utf8');
function setup() {
  const context = vm.createContext({});
  vm.runInContext(`let editing = true, tool = 'connect', selected, connectionOrigin = null;
    const hint = {}; const $ = () => hint; let checkpoints = 0;
    const current = {layout:{links:[], nodes:[]}}; let draft;
    function openLinkDialog(link, parallel) { draft = {link, parallel}; }
    function draw() {} function checkpoint() { checkpoints++; }
    function setTool(mode) {tool = mode; connectionOrigin = null;}
    const a = {id:'a'}, b = {id:'b'};`, context);
  vm.runInContext(source.slice(source.indexOf('function connectEndpoint('), source.indexOf('function drawPreview(')), context);
  return code => vm.runInContext(code, context);
}
test('multiple links between equipment use different ports and reject occupied ports', () => {
  const run = setup();
  run("connectEndpoint(a, 'a1'); connectEndpoint(b, 'b1');");
  assert.equal(run('current.layout.links.length'), 1);
  assert.equal(run("current.layout.links[0].fromPort"), 'a1');
  run("setTool('connect'); connectEndpoint(a, 'a1');");
  assert.equal(run('connectionOrigin'), null);
  run("connectEndpoint(a, 'a2'); connectEndpoint(b, 'b2');");
  assert.equal(run('current.layout.links.length'), 2);
  assert.equal(run('checkpoints'), 2);
});
test('self links do not complete and legacy center links cannot be duplicated in reverse', () => {
  const run = setup();
  run("connectEndpoint(a, null); connectEndpoint(a, null);");
  assert.equal(run('current.layout.links.length'), 0);
  run('connectEndpoint(b, null)');
  run("setTool('connect'); connectEndpoint(b, null); connectEndpoint(a, null);");
  assert.equal(run('current.layout.links.length'), 1);
  assert.equal(run('checkpoints'), 1);
});

test('PON allows multiple links in either direction and rejects duplicate endpoint pairs', () => {
  const run = setup();
  run("a.ports = [{id:'pon1', connector:'pon'}]; const c = {id:'c'};");
  run("connectEndpoint(a, 'pon1'); connectEndpoint(b, null);");
  run("connectEndpoint(c, null); connectEndpoint(a, 'pon1');");
  run("connectEndpoint(b, null); connectEndpoint(a, 'pon1');");
  assert.equal(run('current.layout.links.length'), 2);
  assert.equal(run('checkpoints'), 2);
});

test('a second group-to-group link opens an independent mapped draft without inserting a duplicate', () => {
  const run = setup();
  run("Object.assign(a,{groupId:'ga',x:0,y:0}); Object.assign(b,{groupId:'gb',x:100,y:0}); current.layout.nodes.push(a,b); connectEndpoint(a,null); connectEndpoint(b,null);");
  run("setTool('connect'); connectEndpoint(b,null); connectEndpoint(a,null);");
  assert.equal(run('current.layout.links.length'), 1);
  assert.equal(run('checkpoints'), 1);
  assert.equal(run('draft.parallel'), true);
  assert.equal(run('draft.link.style'), 'corners');
  assert.equal(run('draft.link.points[0].x'), 50);
  assert.notEqual(run('draft.link.points[0].y'), 0);
});
