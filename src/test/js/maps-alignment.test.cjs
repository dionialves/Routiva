const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/maps.js'), 'utf8');
const start = source.indexOf('function alignDraggedNode(');
const end = source.indexOf('\nfunction draw()', start);
const align = vm.runInNewContext(source.slice(start, end) + '\nalignDraggedNode;');

test('dragging snaps both axes and draws guides through aligned peers', () => {
  const node = { id: 'moving', x: 0, y: 0 };
  const peers = [node, { id: 'a', x: 100, y: 200 }, { id: 'b', x: 300, y: 80 }];
  const guides = align(node, 104, 77, peers, 1);
  assert.equal(node.x, 100);
  assert.equal(node.y, 80);
  assert.equal(guides.length, 2);
  assert.equal(guides[0].x1, guides[0].x2);
  assert.equal(guides[1].y1, guides[1].y2);
  assert.ok(guides[0].y2 > 200);
  assert.ok(guides[1].x2 > 300);
});

test('alignment uses the nearest peer and a screen-sized tolerance at each zoom', () => {
  const node = { id: 'moving', x: 0, y: 0 };
  const peers = [node, { id: 'a', x: 100, y: 200 }, { id: 'b', x: 104, y: 300 }];
  align(node, 103, 70, peers, 1);
  assert.equal(node.x, 104);
  assert.equal(node.y, 70);
  assert.equal(align(node, 108, 70, peers, 2).length, 0);
  assert.equal(align(node, 114, 70, peers, 0.5).length, 1);
  assert.equal(node.x, 104);
});

test('a lone element does not align to its own previous position', () => {
  const node = { id: 'moving', x: 100, y: 100 };
  assert.equal(align(node, 102, 103, [node], 1).length, 0);
  assert.equal(node.x, 102);
  assert.equal(node.y, 103);
});
