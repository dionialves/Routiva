const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../../main/resources/static/maps.js'), 'utf8');
function setup() {
  const elements = new Map();
  const $ = id => {
    if (!elements.has(id)) elements.set(id, {value:'',open:false,options:[],append(option){this.options.push(option);},setAttribute(){},removeAttribute(){},focus(){},replaceChildren(...options){this.options=options;},
      showModal(){this.open=true;},close(){this.open=false;this.onclose?.();}});
    return elements.get(id);
  };
  const link = {from:'a',to:'b',fromPort:'r1',style:'corners',points:[{x:10,y:80}]};
  const context = vm.createContext({$, link, editing:true, pendingLink:null, selectedLink:null, selected:null,
    window:{}, Option:function(text,value){this.textContent=text;this.value=value;}, savingConnection:false, loadConnectionInterfaces(){ context.updateLinkEndpoints(); }, lastConnectionClick:null,selection:new Set(),checkpoints:0,draw(){},svg(tag, attributes) {
      return {tag, attributes, children: [], classList:{toggle(){}},
        getAttribute(name){return this.attributes[name];}, setAttribute(name,value){this.attributes[name]=value;},
        getTotalLength:()=>660, replaceChildren(){this.children=[];}, append(...items) {this.children.push(...items);},
        getBBox:()=>({x:0,y:0,width:24,height:24})};
    },
    current:{layout:{nodes:[{id:'a',hostId:'h1',name:'SW',ports:[{id:'r1',type:'rj45'}]},
      {id:'b',groupId:'g1',name:'Grupo'}],links:[link]}},
    inventory:[{id:'h1',name:'Switch Core'}],groups:[{id:'g1',name:'Datacenter'}],
    MapLinks:require('../../main/resources/static/map-links.js'),
    colors:['gray', 'green'],
    MapTraffic:require('../../main/resources/static/map-traffic.js'),
    MapSwitch:require('../../main/resources/static/map-switch.js')
  });
  vm.runInContext('function checkpoint(){checkpoints++;} function linkRoute(){return [{x:0,y:0},{x:200,y:0}];}', context);
  vm.runInContext(source.slice(source.indexOf('const labelPositions'), source.indexOf('function alignDraggedNode(')), context);
  vm.runInContext(source.slice(source.indexOf('function connectionEndpointLabel('),source.indexOf('function switchDraft(')), context);
  return {context,$,run:code=>vm.runInContext(code,context)};
}
test('modal shows endpoint bindings and ports; cancel leaves the connection unchanged', () => {
  const {$,run,context} = setup();
  run('openLinkDialog(link)');
  assert.equal($('link-dialog').open,true);
  assert.equal($('link-from').value, 'Switch Core');
  assert.equal($('link-from').title, 'Switch Core');
  assert.equal($('link-to').value, 'Datacenter');
  $('link-dialog-style').value='dashed-pulses';
  $('cancel-link').onclick();
  assert.equal(context.link.lineStyle,undefined);
  assert.equal(context.checkpoints,0);
});
test('endpoint thumbnails use the map renderer with the saved icon, tint and orientation', () => {
  const {$, run} = setup();
  run(`Object.assign(current.layout.nodes[0], {icon:'switch', rotation:15, switchColor:'#abcedf'});
    Object.assign(current.layout.nodes[1], {icon:'sector-radio', flipX:true});
    openLinkDialog(link);`);
  const switchDrawing = $('link-from-art').options[0].children[0];
  const radioDrawing = $('link-to-art').options[0].children[0];
  assert.match(switchDrawing.attributes.transform, /rotate\(15\)/);
  const switchImage = switchDrawing.children.find(item => item.tag === 'image');
  assert.match(switchImage.attributes.href, /\/switch\.svg/);
  assert.match(switchImage.attributes.filter, /switch-tint/);
  assert.match(radioDrawing.attributes.transform, /scale\(-1 1\)/);
  assert.match(radioDrawing.children.find(item => item.tag === 'image').attributes.href, /\/sector-radio\.webp/);
});

test('smooth geometry and dashed pulses preview independently and preserve waypoints', async () => {
  const {$,run,context} = setup();
  run('openLinkDialog(link)');
  const previousPath = $('link-preview').options[0].attributes.d;
  $('link-dialog-type').value = 'smooth';
  $('link-dialog-type').onchange();
  const smoothPath = $('link-preview').options[0].attributes.d;
  assert.notEqual(smoothPath, previousPath);
  assert.match(smoothPath, / Q /);
  $('link-dialog-style').value = 'dashed-pulses';
  $('link-dialog-style').onchange();
  const [line, layer] = $('link-preview').options;
  assert.equal(line.attributes.d, smoothPath);
  assert.equal(line.attributes.class, 'link-preview-wireless');
  assert.equal(layer.trafficPulses.attributes.visibility, 'visible');
  await $('link-form').onsubmit({preventDefault(){}});
  assert.equal(context.link.lineStyle, 'curved');
  assert.equal(context.link.style, 'corners');
  assert.equal(context.link.appearance, 'dashed-pulses');
  assert.deepEqual(context.link.points, [{x:10,y:80}]);
  assert.equal(context.checkpoints, 1);
  assert.equal($('link-dialog').open, false);
  run('openLinkDialog(link)');
  assert.equal($('link-dialog-type').value, 'smooth');
  assert.equal($('link-dialog-style').value, 'dashed-pulses');
});

test('connection settings only open in edit mode', () => {
  const {$,run,context} = setup();
  context.editing = false;
  run('openLinkDialog(link)');
  assert.equal($('link-dialog').open, false);
  assert.equal(context.pendingLink, null);
  context.editing = true;
  run('openLinkDialog(link)');
  assert.equal($('link-dialog').open, true);
});


test('delete in the connection modal uses confirmation and records one undo step',()=>{
  const {$,context,run}=setup();
  vm.runInContext(source.slice(source.indexOf('function deleteSelection('),source.indexOf("$('delete-item').onclick")),context);
  context.confirm=()=>false;
  run('openLinkDialog(link)');
  $('delete-link').onclick();
  assert.equal(context.current.layout.links.length,1);
  assert.equal($('link-dialog').open,true);
  assert.equal(context.checkpoints,0);
  context.confirm=()=>true;
  $('delete-link').onclick();
  assert.equal(context.current.layout.links.length,0);
  assert.equal(context.current.layout.nodes.length,2);
  assert.equal($('link-dialog').open,false);
  assert.equal(context.checkpoints,1);
});

test('endpoint summaries follow interface selection without changing the link', () => {
  const {$, run, context} = setup();
  run('openLinkDialog(link)');
  $('link-category').value = 'mapped';
  $('connection-interface-a').value = 'sfp-sfpplus2';
  $('connection-interface-b').value = 'ether3';
  run('updateLinkEndpoints()');
  assert.equal($('link-from-interface').textContent, 'sfp-sfpplus2');
  assert.equal($('link-to-interface').textContent, 'ether3');
  assert.equal($('link-from-identity').textContent, ' · ID h1');
  assert.equal($('link-to-identity').textContent, ' · ID g1');
  assert.match($('connection-host-a').textContent, /Switch Core/);
  assert.equal($('link-from-capacity').hidden, true);
  assert.equal(context.checkpoints, 0);
});
test('header close dismisses the dialog without applying draft appearance', () => {
  const {$, run, context} = setup();
  run('openLinkDialog(link)');
  $('link-dialog-style').value = 'dashed';
  $('close-link').onclick();
  assert.equal($('link-dialog').open, false);
  assert.equal(context.link.lineStyle, undefined);
  assert.equal(context.checkpoints, 0);
});

test('endpoint badge uses measured speed and never infers speed from port capacity', () => {
  const {$, run} = setup();
  run("current.layout.nodes[0].ports[0] = {id:'r1', name:'ether1', connector:'electrical', capacity:'10g'}; openLinkDialog(link)");
  assert.equal($('link-from-capacity').hidden, true);
  run("current.layout.nodes[0].ports[0].speed = '1Gbps'; updateLinkEndpoints()");
  assert.equal($('link-from-capacity').hidden, false);
  assert.equal($('link-from-capacity').textContent, '1Gbps');
  assert.equal($('connection-host-a').textContent, 'Switch Core · ID h1');
});

test('swap moves hosts, port summaries and complete interface choices only in the draft', () => {
  const {$, run, context} = setup();
  run('openLinkDialog(link)');
  $('link-category').value = 'mapped';
  $('connection-interface-a').options = [{value:'ether1', text:'Origem'}];
  $('connection-interface-b').options = [{value:'sfp1', text:'Destino'}];
  $('connection-interface-a').value = 'ether1';
  $('connection-interface-b').value = 'sfp1';
  $('swap-connection-endpoints').onclick();
  assert.equal($('link-from').value, 'Datacenter');
  assert.equal($('connection-interface-a').value, 'sfp1');
  assert.equal($('connection-interface-a').options[0].text, 'Destino');
  assert.equal($('connection-interface-b').value, 'ether1');
  assert.equal(context.link.from, 'a');
  assert.equal(context.link.fromPort, 'r1');
  $('swap-connection-endpoints').onclick();
  assert.equal($('link-from').value, 'Switch Core');
  assert.equal($('connection-interface-a').value, 'ether1');
  $('cancel-link').onclick();
  assert.equal(context.checkpoints, 0);
});
test('saving a swapped mapped connection sends matching hosts and interfaces and reverses the route', async () => {
  const {$, run, context} = setup();
  let request;
  context.connectionDefinitions = [{id:'old', name:'Uplink', hostA:'h1', hostB:'h2', interfaceA:'ether1', interfaceB:'sfp1'}];
  context.connectionNodes = link => [link.from, link.to].map(id => context.current.layout.nodes.find(n => n.id === id));
  context.connectionRequest = async (url, body) => { assert.equal(url, '/api/maps/connections/validate'); request = body; return {...body, id:'new'}; };
  run("current.layout.nodes[1].hostId='h2'; link.toPort='p2'; link.points=[{x:10,y:80},{x:90,y:80}]; link.mappedConnectionId='old'; openLinkDialog(link)");
  $('connection-name').value = 'Uplink';
  $('connection-interface-a').value = 'ether1';
  $('connection-interface-b').value = 'sfp1';
  $('swap-connection-endpoints').onclick();
  await $('link-form').onsubmit({preventDefault(){}});
  assert.deepEqual({...request}, {name:'Uplink', hostA:'h2', hostB:'h1', interfaceA:'sfp1', interfaceB:'ether1'});
  assert.equal(context.link.from, 'b');
  assert.equal(context.link.to, 'a');
  assert.equal(context.link.fromPort, 'p2');
  assert.equal(context.link.toPort, 'r1');
  assert.equal(context.link.points[0].x, 90);
  assert.equal(context.link.mappedConnectionId, 'new');
  assert.deepEqual({...context.link.connection}, {...request, id:'new'});
  assert.equal(context.checkpoints, 1);
});
test('swap is unavailable during loading and saving', () => {
  const {$, run} = setup();
  run('openLinkDialog(link)');
  $('connection-interface-a').disabled = true;
  $('swap-connection-endpoints').onclick();
  assert.equal($('link-from').value, 'Switch Core');
  $('connection-interface-a').disabled = false;
  run('savingConnection=true');
  $('swap-connection-endpoints').onclick();
  assert.equal($('link-from').value, 'Switch Core');
});

test('cancel after swapping preserves saved endpoints and port bindings', () => {
  const {$, run, context} = setup();
  run('openLinkDialog(link)');
  $('swap-connection-endpoints').onclick();
  assert.equal($('link-from').value, 'Datacenter');
  $('cancel-link').onclick();
  assert.equal(context.link.from, 'a');
  assert.equal(context.link.to, 'b');
  assert.equal(context.link.fromPort, 'r1');
  assert.equal(context.checkpoints, 0);
  run('openLinkDialog(link)');
  assert.equal($('link-from').value, 'Switch Core');
});


function groupedConnectionSetup() {
  const ui = setup();
  ui.context.inventory = [
    {id:'h1', name:'Core', groups:[{id:'g1'}]},
    {id:'h2', name:'Radio', groups:[{id:'g2'}]},
    {id:'outside', name:'Outside', groups:[{id:'other'}]}
  ];
  ui.context.connectionDefinitions = [];
  ui.run("current.layout.nodes=[{id:'a',groupId:'g1',name:'Site A'},{id:'b',groupId:'g2',name:'Site B'},{id:'host1',hostId:'h1',ports:[{name:'ether1'},{name:'ether2'}]},{id:'host2',hostId:'h2',ports:[{name:'ether1'},{name:'ether2'}]}]; openLinkDialog(link)");
  vm.runInContext(source.slice(source.indexOf('function connectionNodes('), source.indexOf("$('link-category').onchange")), ui.context);
  return ui;
}

test('mapped group endpoints list only member hosts and load interfaces from the selected hosts', async () => {
  const {$, context, run} = groupedConnectionSetup();
  assert.deepEqual($('connection-host-select-a').options.map(o => o.value), ['', 'h1']);
  assert.deepEqual($('connection-host-select-b').options.map(o => o.value), ['', 'h2']);
  $('link-category').value='mapped';
  const requests=[];
  context.connectionRequest=async url => {requests.push(url);return [{name:'ether1'}];};
  await run('loadConnectionInterfaces()');
  assert.equal(requests.length, 0);
  $('connection-host-select-a').value='h1';
  $('connection-host-select-b').value='h2';
  await run('loadConnectionInterfaces()');
  assert.deepEqual(requests, []);
  assert.deepEqual($('connection-interface-a').options.map(o=>o.value), ['', 'ether1', 'ether2']);
  assert.equal($('connection-interface-a').disabled,false);
  $('connection-interface-a').value='ether1';
  $('connection-interface-b').value='ether1';
  let saved;
  context.connectionRequest=async (url, body) => {saved=body;return {...body,id:'mapped'};};
  await $('link-form').onsubmit({preventDefault(){}});
  assert.equal(saved.hostA,'h1');
  assert.equal(saved.hostB,'h2');
  assert.equal(context.link.connection.interfaceA,'ether1');
});

test('changing group host invalidates interfaces and discards an in-flight interface response', async () => {
  const {$,context,run}=groupedConnectionSetup();
  $('link-category').value='mapped';
  $('connection-host-select-a').value='h1';
  $('connection-host-select-b').value='h2';
  // One deferred request per side, released together.
  const pending=[];
  context.connectionRequest=()=>new Promise(resolve=>pending.push(resolve));
  const loading=run('loadConnectionInterfaces()');
  $('connection-host-select-a').value='';
  $('connection-host-select-a').onchange();
  pending.forEach(resolve=>resolve([{name:'stale'}]));
  await loading;
  assert.equal($('connection-interface-a').options.length,0);
  assert.equal($('connection-interface-a').disabled,true);
  assert.equal($('connection-host-select-a').value,'');
});

test('swapping grouped endpoints preserves the chosen hosts in the draft', () => {
  const {$,run}=groupedConnectionSetup();
  $('connection-host-select-a').value='h1';
  $('connection-host-select-b').value='h2';
  $('connection-interface-a').value='ether1';
  $('connection-interface-b').value='ether2';
  $('swap-connection-endpoints').onclick();
  assert.equal($('connection-host-select-a').value,'h2');
  assert.equal($('connection-host-select-b').value,'h1');
  assert.equal(run('connectionHostId(current.layout.nodes[1],0)'), 'h2');
  assert.equal($('connection-interface-a').value,'ether2');
});


test('reopening a mapped group connection restores both saved hosts and their interfaces', async () => {
  const {$,context,run}=groupedConnectionSetup();
  run("link.connection={id:'saved',name:'Enlace',hostA:'h1',hostB:'h2',interfaceA:'ether1',interfaceB:'ether2'}; link.mappedConnectionId='saved'; prepareConnectionHosts(true)");
  assert.equal($('connection-host-select-a').value,'h1');
  assert.equal($('connection-host-select-b').value,'h2');
  $('link-category').value='mapped';
  context.connectionRequest=async()=>[{name:'ether1'},{name:'ether2'}];
  await run('loadConnectionInterfaces()');
  assert.equal($('connection-interface-a').value,'ether1');
  assert.equal($('connection-interface-b').value,'ether2');
  $('connection-host-select-a').value='outside';
  assert.equal(run('connectionHostId(current.layout.nodes[0],0)'),null);
});

test('map dialog loads shared picker positioning for viewport-based popovers', () => {
  const path = require('node:path');
  const template = fs.readFileSync(path.join(__dirname, '../../main/resources/templates/maps.html'), 'utf8');
  const shared = fs.readFileSync(path.join(__dirname, '../../main/resources/templates/design-system.html'), 'utf8');
  const css = fs.readFileSync(path.join(__dirname, '../../main/resources/static/multi-picker.css'), 'utf8');
  assert.match(template, /th:replace="~\{design-system :: picker\}"/);
  assert.match(shared, /th:fragment="picker"[^>]+href="\/multi-picker\.css\?/);
  assert.match(css, /\.group-picker-panel\[popover\]\s*\{[^}]*position:\s*fixed;[^}]*margin:\s*0;/);
});

test('connection options include only saved element ports and discard removed interface selections', async () => {
  const {$,context,run}=groupedConnectionSetup();
  run("current.layout.nodes.find(n=>n.hostId==='h1').ports=[{name:'eth1_emac1'},{name:'wifi0'}];link.connection={id:'c',name:'Enlace',hostA:'h1',hostB:'h2',interfaceA:'wlan0',interfaceB:'ether1'};link.mappedConnectionId='c';prepareConnectionHosts(true)");
  $('link-category').value='mapped';
  context.connectionRequest=()=>assert.fail('loading element ports must not query the server');
  await run('loadConnectionInterfaces()');
  assert.deepEqual($('connection-interface-a').options.map(o=>o.value),['','eth1_emac1','wifi0']);
  assert.equal($('connection-interface-a').value,'');
});

test('group connections use the enabled registry without individual host elements or discovery',async()=>{
  const {$,context,run}=groupedConnectionSetup();
  run("current.layout.nodes=current.layout.nodes.filter(n=>!n.hostId);current.layout.nodes[0].linkInterfaces=[{hostId:'h1',name:'wifi0'},{hostId:'h1',name:'eth1_emac1'}];current.layout.nodes[1].linkInterfaces=[{hostId:'h2',name:'wlan0'}];prepareConnectionHosts()");
  $('link-category').value='mapped';$('connection-host-select-a').value='h1';$('connection-host-select-b').value='h2';
  context.connectionRequest=()=>assert.fail('no discovery while configuring a link');
  await run('loadConnectionInterfaces()');
  assert.deepEqual($('connection-interface-a').options.map(o=>o.value),['','wifi0','eth1_emac1']);
  assert.deepEqual($('connection-interface-b').options.map(o=>o.value),['','wlan0']);
});
