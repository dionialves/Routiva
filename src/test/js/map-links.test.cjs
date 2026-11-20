const {test} = require('node:test');
const assert = require('node:assert/strict');
const links = require('../../main/resources/static/map-links.js');

test('straight routes ignore bends and follow moving endpoints', () => {
  const start = {x: 10, y: 20}, end = {x: 300, y: 40};
  assert.deepEqual(links.points({style: 'straight', points: [{x: 0, y: 100}]}, start, end), [start, end]);
  start.x = 50;
  assert.equal(links.points({}, start, end)[0].x, 50);
});
test('corner routes retain every user waypoint in order', () => {
  const start = {x: 0, y: 0}, end = {x: 100, y: 0};
  const points = links.initial(start, end);
  assert.deepEqual(points, [{x: 0, y: 80}, {x: 100, y: 80}]);
  for (let i = 0; i < 50; i++) points.push({x: i, y: 90});
  const route = links.points({style: 'corners', points}, start, end);
  assert.equal(route.length, 54);
  assert.equal(route[1], points[0]);
  assert.equal(route.at(-1), end);
});
test('insertion finds closest segment including vertical and zero length segments', () => {
  const route = [{x: 0, y: 0}, {x: 0, y: 100}, {x: 200, y: 100}, {x: 200, y: 0}];
  assert.equal(links.insertion(route, {x: 3, y: 40}), 0);
  assert.equal(links.insertion(route, {x: 80, y: 98}), 1);
  assert.equal(links.insertion(route, {x: 198, y: 45}), 2);
  assert.equal(links.insertion([{x: 0,y: 0},{x: 0,y: 0}], {x: 1,y: 1}), 0);
  assert.deepEqual(links.midpoint(route), {x: 100, y: 100});
});

test('waypoints snap independently to adjacent horizontal and vertical coordinates', () => {
  const route = [{x: 10,y: 20},{x: 70,y: 70},{x: 100,y: 120}];
  const result = links.snap(route, 1, {x: 97,y: 24}, 1);
  assert.deepEqual(result.position, {x: 100,y: 20});
  assert.equal(result.guides.length, 2);
  assert.deepEqual(links.snap(route, 1, {x: 80,y: 50}, 1).position, {x: 80,y: 50});
});
test('snap distance follows zoom and ignores nonadjacent corners', () => {
  const route = [{x: 10,y: 20},{x: 70,y: 70},{x: 100,y: 120},{x: 80,y: 50}];
  assert.deepEqual(links.snap(route, 1, {x: 97,y: 24}, 3).position, {x: 97,y: 24});
  assert.deepEqual(links.snap(route, 1, {x: 80,y: 50}, 1).guides, []);
  assert.deepEqual(links.snap(route, 1, {x: 89,y: 20}, 0.5).position, {x: 100,y: 20});
});

test('straight appearance preserves sharp segments and curved appearance rounds corners', () => {
  const start = {x: 0,y: 0}, end = {x: 100,y: 100};
  const link = {style:'corners', points:[{x:0,y:100}]};
  assert.equal(links.path(link, start, end), 'M 0 0 L 0 100 L 100 100');
  link.lineStyle = 'curved';
  assert.equal(links.path(link, start, end), 'M 0 0 L 0 82 Q 0 100 18 100 L 100 100');
  assert.deepEqual(link.points, [{x:0,y:100}]);
});
test('curves stay finite with repeated waypoints and short segments', () => {
  const link = {style:'corners', lineStyle:'curved', points:[{x:0,y:0},{x:0,y:2},{x:1,y:2}]};
  const path = links.path(link, {x:0,y:0}, {x:1,y:4});
  assert.ok(!/NaN|Infinity/.test(path));
  assert.ok(path.endsWith('L 1 4'));
  assert.match(path, /Q/);
  assert.match(links.path({lineStyle:'curved'}, {x:10,y:20}, {x:100,y:120}), /^M 10 20 C .*100 120$/);
});

test('bend marks trace the real corner or rounded arc without a separate point glyph',()=>{
  const start={x:0,y:0},end={x:100,y:100};
  const link={style:'corners',points:[{x:0,y:100}],lineStyle:'straight'};
  assert.equal(links.bendPath(link,start,end,0),'M 0 90 L 0 100 L 10 100');
  link.lineStyle='curved';
  assert.equal(links.bendPath(link,start,end,0),'M 0 82 Q 0 100 18 100');
  assert.ok(links.path(link,start,end).includes('Q 0 100 18 100'));
  link.points=[{x:0,y:0}];
  assert.ok(!/NaN|Infinity/.test(links.bendPath(link,start,end,0)));
});

test('nearby horizontal and vertical lines attract corners to exact coordinates', () => {
  const route = [{x:0,y:0},{x:40,y:80},{x:100,y:100}];
  const others = [[{x:10,y:85.5},{x:150,y:85.5}], [{x:45.5,y:20},{x:45.5,y:150}]];
  const result = links.snap(route, 1, {x:40,y:80}, 1, others);
  assert.deepEqual(result.position, {x:45.5,y:85.5});
  assert.equal(result.guides.length, 2);
  assert.deepEqual(others[0], [{x:10,y:85.5},{x:150,y:85.5}]);
});

test('line attraction respects zoom, overlap and nearest segment', () => {
  const route = [{x:0,y:0},{x:40,y:80},{x:100,y:100}];
  const far = [[{x:500,y:84},{x:600,y:84}]];
  assert.equal(links.snap(route, 1, {x:40,y:80}, 1, far).position.y, 80);
  const others = [[{x:10,y:87},{x:150,y:87}], [{x:10,y:84},{x:150,y:84}]];
  assert.equal(links.snap(route, 1, {x:40,y:80}, 1, others).position.y, 84);
  assert.equal(links.snap(route, 1, {x:40,y:80}, 3, others).position.y, 80);
  assert.equal(links.snap(route, 1, {x:40,y:80}, 0.5, others).position.y, 84);
});
