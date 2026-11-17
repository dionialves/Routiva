const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../../main/resources/static/maps.js'), 'utf8');

test('all corner links keep editable handles when another element or link is selected', () => {
  const canvas = {children: [], dataset: {}, replaceChildren() {this.children = [];},
    append(...children) {this.children.push(...children);}, querySelector() {return null;}};
  const controls = new Map([['canvas', canvas]]);
  const context = vm.createContext({
    document: {activeElement: null},
    MapLinks: require('../../main/resources/static/map-links.js'),
    linkRoute: link => [{x:0,y:0}, ...link.points, {x:100,y:100}],
    $: id => {if (!controls.has(id)) controls.set(id, {setAttribute() {}}); return controls.get(id);},
    svg: (tag, attributes) => ({tag, attributes, append() {}}),
    editing: true, tool: 'select', selected: 'another-element', selectedLink: null,
    selection: new Set(['another-element']), drag: null, marquee: null, connectionOrigin: null,
    past: [], future: [], camera: {scale: 1}, checkpoint() {},
    current: {layout: {name: 'Test', nodes: [], links: [
      {style: 'corners', points: [{x:10,y:20}]},
      {style: 'corners', points: [{x:30,y:40}]},
      {style: 'straight', points: [{x:50,y:60}]}
    ]}}
  });
  vm.runInContext(source.slice(source.indexOf('function draw()'), source.indexOf('\nfunction choose(')), context);
  vm.runInContext('draw()', context);
  const handles = () => canvas.children.filter(child => child.attributes.class === 'map-link-handle');
  assert.equal(handles().length, 2);
  assert.ok(handles().every(handle=>handle.tag==='path'));
  vm.runInContext('selectedLink = current.layout.links[1]; draw()', context);
  assert.equal(handles().length, 2);
  handles()[0].ondblclick({stopPropagation() {}});
  assert.equal(context.current.layout.links[0].points.length, 0);
  assert.equal(context.current.layout.links[1].points.length, 1);
});
