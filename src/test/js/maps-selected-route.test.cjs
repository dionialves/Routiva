const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../../main/resources/static/maps.js'), 'utf8');

test('click and keyboard bring the selected connection above other routes without reordering saved links', () => {
  function svg(tag, attributes) {
    const classes = new Set((attributes.class || '').split(' '));
    return {tag, attributes, children: [], dataset: {},
      append(...items) {this.children.push(...items);},
      getAttribute(key) {return this.attributes[key];},
      setAttribute(key, value) {this.attributes[key] = value;},
      classList: {toggle(name, enabled) {if (enabled) classes.add(name); else classes.delete(name);},
        contains(name) {return classes.has(name);}}};
  }
  const canvas = svg('svg', {});
  canvas.replaceChildren = () => {canvas.children = [];};
  const links = [{from:'a',to:'b'}, {from:'b',to:'a'}];
  const context = vm.createContext({document:{activeElement:null}, svg,
    $: id => id === 'canvas' ? canvas : {setAttribute() {}},
    current:{layout:{name:'Map',nodes:[{id:'a',name:'A'},{id:'b',name:'B'}],links}},
    editing:false, drag:null, marquee:null, selected:null, selectedLink:null, selection:new Set(),
    MapSwitch:{...require('../../main/resources/static/map-switch.js'),endpoint:() => ({x:0,y:0})}, MapLinks:{path:link => link === links[0] ? 'first' : 'second'},
    MapConnectionTooltip:{hide() {}}, updateTrafficLayer(layer) {layer.updated = true;},
    MapTraffic:{synchronizePulses(root) { context.synchronized = root; }, pulsePhases:require('../../main/resources/static/map-traffic.js').pulsePhases, isWireless:() => false, maskOverlaps(svg, connectors, layers, selectedRoute) { context.maskedRoute = selectedRoute; }}, colors:['gray','green'], labels:['','OK'], drawNode() {}});
  vm.runInContext(source.slice(source.indexOf('function drawMapElement('), source.indexOf('function alignDraggedNode(')), context);
  vm.runInContext(source.slice(source.indexOf('function draw()'), source.indexOf('\nfunction choose(')), context);
  const routes = () => canvas.children.find(child => child.attributes.class === 'map-connectors').children;
  vm.runInContext('draw()', context);
  routes()[0].onclick({stopPropagation() {}});
  assert.equal(routes().at(-3).attributes.class, 'map-connector-selection-border');
  assert.equal(routes().at(-3).attributes.d, 'first');
  assert.equal(routes().at(-2).attributes.d, 'first');
  assert.ok(routes().at(-2).classList.contains('selected-link'));
  assert.equal(routes().at(-2).attributes['aria-pressed'], 'true');
  routes()[0].onkeydown({key:'Enter',preventDefault() {},stopPropagation() {}});
  assert.equal(routes().at(-2).attributes.d, 'second');
  assert.equal(links[0].from, 'a');
  links[1].mappedConnectionId = 'mapped';
  context.MapLinks.points = () => [];
  vm.runInContext('draw()', context);
  assert.equal(routes().at(-1).attributes.class, 'map-traffic');
  assert.equal(routes().at(-1).updated, true);
  assert.equal(context.synchronized, canvas.children.find(child => child.attributes.class === 'map-connectors'));
  assert.equal(routes().at(-1).trafficContext.link, links[1]);
  assert.equal(routes().at(-1).trafficContext.selected, true);
  assert.equal(context.maskedRoute, 'second');
  assert.equal(routes().at(-4).attributes.class, 'map-connector-selection-border');
  context.MapTraffic.isWireless = () => true;
  vm.runInContext('draw()', context);
  assert.equal(routes().at(-4).attributes.class, 'map-connector-selection-border map-connector-wireless');
  assert.ok(routes().at(-3).classList.contains('map-connector-wireless'));
  assert.ok(routes().at(-3).classList.contains('selected-link'));
  assert.equal(routes().at(-1).attributes.class, 'map-traffic');
  assert.equal(routes().at(-1).trafficContext.wireless, true);
  assert.equal(routes().at(-1).updated, true);
  // A mapped route saved first must still cover a simple route saved last.
  delete links[1].mappedConnectionId;
  links[0].mappedConnectionId = 'mapped';
  vm.runInContext('selectedLink = null; draw()', context);
  const painted = routes().filter(path => path.classList.contains('map-connector'));
  assert.equal(painted[0].attributes.d, 'second');
  assert.equal(painted[1].attributes.d, 'first');
  assert.equal(links[0].mappedConnectionId, 'mapped');
  painted[0].onclick({stopPropagation() {}});
  const selectedRoutes = routes().filter(path => path.classList.contains('map-connector'));
  assert.equal(selectedRoutes.at(-1).attributes.d, 'second');
  assert.ok(selectedRoutes.at(-1).classList.contains('selected-link'));
  assert.equal(context.maskedRoute, 'second');
  selectedRoutes.at(-1).onclick({stopPropagation() {}});
  assert.equal(context.selectedLink, null);
  assert.ok(routes().every(path => !path.attributes.class.includes('map-connector-selection-border')));
  const mapped = routes().filter(path => path.classList.contains('map-connector')).at(-1);
  mapped.onclick({stopPropagation() {}});
  assert.equal(context.selectedLink, links[0]);
  routes().filter(path => path.classList.contains('map-connector')).at(-1).onkeydown({key:'Enter',preventDefault() {},stopPropagation() {}});
  assert.equal(context.selectedLink, null);
  Object.assign(context, {editing:true, tool:'select', past:[], future:[],
    suppressConnectionUntil:0, lastConnectionClick:null});
  vm.runInContext('selectedLink = null; draw()', context);
  routes()[0].onclick();
  const editorRoutes = routes().filter(path => path.classList.contains('map-connector'));
  assert.ok(editorRoutes.every(path => !path.classList.contains('selected-link')));
  assert.ok(routes().every(path => !path.attributes.class.includes('map-connector-selection-border')));
  assert.equal(editorRoutes.at(-1).attributes['aria-pressed'], 'true');
  assert.equal(context.selectedLink, links[1]);
  // A separate click deselects in the editor; double-click still opens settings.
  context.lastConnectionClick = null;
  editorRoutes.at(-1).onclick();
  assert.equal(context.selectedLink, null);
});
