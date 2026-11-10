const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/maps.js'), 'utf8');
const context = vm.createContext({ current: { layout: { minimumSeverity: 2 } } });
vm.runInContext(source.slice(source.indexOf('function hostLevel('), source.indexOf('function checkpoint(')), context);
const host = incidents => ({ incidents: incidents.map(severity => ({ severity })) });

test('minimum severity ignores lower incidents and includes the selected level', () => {
  context.host = host([0, 1]);
  assert.equal(vm.runInContext('hostLevel(host)', context), -1);
  context.host = host([0, 1, 2]);
  assert.equal(vm.runInContext('hostLevel(host)', context), 2);
  context.host = host([1, 5]);
  assert.equal(vm.runInContext('hostLevel(host)', context), 5);
});

test('groups apply the same filter and retain missing-data states', () => {
  context.hosts = [host([0]), host([1])];
  assert.equal(vm.runInContext('groupLevel(hosts)', context), -1);
  context.hosts.push(host([4]));
  assert.equal(vm.runInContext('groupLevel(hosts)', context), 4);
  context.hosts = [host([1]), {}];
  assert.equal(vm.runInContext('groupLevel(hosts)', context), -2);
  assert.equal(vm.runInContext('groupLevel([])', context), -2);
});

test('legacy maps include all severities by default', () => {
  context.current = { layout: {} };
  context.host = host([0]);
  assert.equal(vm.runInContext('hostLevel(host)', context), 0);
});

test('POP colors respect map threshold, including information and unknown monitoring', () => {
  context.current = {layout: {minimumSeverity: 1}};
  context.hosts = [host([0])];
  assert.equal(vm.runInContext('groupLevel(hosts)', context), -1);
  context.hosts.push(host([1]));
  assert.equal(vm.runInContext('groupLevel(hosts)', context), 1);
  context.current.layout.minimumSeverity = 5;
  assert.equal(vm.runInContext('groupLevel(hosts)', context), -1);
  context.hosts = [{incidents: [{severity: 0}], monitoring: 'unknown'}];
  assert.equal(vm.runInContext('groupLevel(hosts)', context), -2);
});

test('draw paints POP circles using the map threshold rather than unclassified incidents', () => {
  const canvas = {children: [], replaceChildren() { this.children = []; }, append(child) { this.children.push(child); }, dataset: {}};
  const elements = new Map([['canvas', canvas], ['map-title', {}]]);
  const node = {id: 'pop', groupId: 'g', name: 'POP', x: 0, y: 0};
  const hosts = [{incidents: [{severity: 0}], monitoring: 'monitored'}];
  const drawContext = vm.createContext({
    current: {layout: {name: 'Map', minimumSeverity: 1, nodes: [node], links: []}},
    editing: false, drag: null, marquee: null, selection: new Set(), selected: null,
    colors: ['gray', 'green', 'unclassified', 'information'],
    labels: ['Sem dados', 'Sem incidentes', 'Não classificado', 'Informação'],
    $: id => elements.get(id), nodeHosts: () => hosts,
    svg: (tag, attributes) => ({tag, attributes, children: [], append(...children) { this.children.push(...children); }}),
    popSummary: {bind() {}},
    drawNode: (element, name, position, fill) => { element.fill = fill; }
  });
  vm.runInContext(source.slice(source.indexOf('function hostLevel('), source.indexOf('function checkpoint(')), drawContext);
  vm.runInContext(source.slice(source.indexOf('function draw()'), source.indexOf('\nfunction choose(')), drawContext);
  vm.runInContext('draw()', drawContext);
  assert.equal(canvas.children[0].fill, 'green');
  assert.equal(canvas.children[0].attributes['aria-label'], 'POP · Sem incidentes');
  hosts[0].incidents.push({severity: 1});
  vm.runInContext('draw()', drawContext);
  assert.equal(canvas.children[0].fill, 'information');
  hosts[0].incidents = [{severity: 0}];
  drawContext.current.layout.minimumSeverity = 0;
  vm.runInContext('draw()', drawContext);
  assert.equal(canvas.children[0].fill, 'unclassified');
});
