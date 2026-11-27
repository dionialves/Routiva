const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const MapSwitch = require('../../main/resources/static/map-switch.js');
const source = fs.readFileSync(require('node:path').join(__dirname,'../../main/resources/static/maps.js'),'utf8');
function setup() {
  const slots=[{id:'slot',name:'ether',capacity:'electrical',count:2}];
  const ports=MapSwitch.portsForSlots([],slots);
  const node={id:'node',name:'Switch',icon:'switch',interfacesEnabled:true,ports,slots};
  const fields=new Map();
  const $=id=>{
    if(!fields.has(id))fields.set(id,{value:'',checked:false,open:false,focus(){},removeAttribute(){},
      replaceChildren(...options){this.options=options;},add(option){this.options.push(option);},showModal(){this.open=true;},close(){this.open=false;},querySelector(){return {scrollTop:0,click(){}};}});
    return fields.get(id);
  };
  $('element-icon').options=[{value:'circle',dataset:{bindings:'group free'}},{value:'switch',dataset:{bindings:'host free'}}];
  $('element-name').value='Switch';$('element-icon').value='switch';$('label-position').value='BELOW';
  $('interfaces-enabled').checked=true;
  const context=vm.createContext({$,MapSwitch,maps:[],mapAncestors:()=>[],Option:function(text,value){this.text=text;this.value=value;},current:{layout:{nodes:[node],links:[{from:'node',to:'other',fromPort:ports[0].id}]}},
    interfaceLoadGeneration:0,loadingInterfaces:false,configuredSlots:slots.map(s=>({...s})),configuredPorts:ports,configuredSwitchColor:null,pendingNode:null,selected:'node',selectedLink:null,
    selection:new Set(),tool:'select',timer:null,generation:0,checkpoint(){},refresh(){},clearTimeout(){},
    draw(){},drawPreview(){},populateBinding(){},renderSwitchSlots(){},window:{elementIconPicker:{sync(){}}},
    confirm(){throw Error('No connections should be removed');}});
  vm.runInContext(source.slice(source.indexOf('function ensureElementOrigin('), source.indexOf('function applyElementAction(')), context);
  vm.runInContext('function switchDraft(){return MapSwitch.portsForSlots(configuredPorts,configuredSlots);}',context);
  vm.runInContext(source.slice(source.indexOf("$('element-form').onsubmit ="),source.indexOf('function deleteSelection(')),context);
  vm.runInContext(source.slice(source.indexOf('function openElement('),source.indexOf('window.elementIconPicker =')),context);
  return {$,context,node,run:code=>vm.runInContext(code,context)};
}
test('changing host clears interface drafts and invalidates pending discovery without changing the saved node',()=>{
  const {$,context,node,run}=setup();
  context.configuredHostId='old';context.loadingInterfaces=true;
  $('type').value='host';$('group').value='new';
  vm.runInContext(source.slice(source.indexOf('function resetInterfacesForHostChange('),source.indexOf("$('type').onchange =")),context);
  run('resetInterfacesForHostChange()');
  assert.equal(context.configuredPorts.length,0);
  assert.equal(context.configuredSlots.length,0);
  assert.equal($('interfaces-enabled').checked,false);
  assert.equal(context.loadingInterfaces,false);
  assert.equal(context.interfaceLoadGeneration,1);
  assert.equal(node.ports.length,2);
  context.configuredSlots=[{id:'new-slot'}];
  run('resetInterfacesForHostChange()');
  assert.equal(context.configuredSlots.length,1);
});
test('saving a different icon and disabled interfaces retains slots, ports and their links',()=>{
  const {$,context,node}=setup();
  const portId=node.ports[0].id;
  $('element-icon').value='circle';$('interfaces-enabled').checked=false;
  $('element-form').onsubmit({preventDefault(){}});
  assert.equal(node.icon,'circle');assert.equal(node.interfacesEnabled,false);
  assert.equal(node.slots.length,1);assert.equal(node.ports[0].id,portId);
  assert.equal(context.current.layout.links.length,1);
});
test('opening restores the explicit flag independently of icon and clones the slot draft',()=>{
  const {$,context,node,run}=setup();
  node.icon='circle';node.interfacesEnabled=true;
  run('openElement(current.layout.nodes[0])');
  assert.equal($('interfaces-enabled').checked,true);
  context.configuredSlots[0].name='edited';
  assert.equal(node.slots[0].name,'ether');
  node.icon='switch';node.interfacesEnabled=false;
  run('openElement(current.layout.nodes[0])');
  assert.equal($('interfaces-enabled').checked,false);
  assert.equal(context.configuredSlots[0].name,'ether');
});

test('custom switch color persists separately from the existing circle color',()=>{
  const {$,context,node}=setup();
  node.color='#123456';context.configuredSwitchColor='#2288cc';
  $('element-form').onsubmit({preventDefault(){}});
  assert.equal(node.switchColor,'#2288cc');assert.equal(node.color,'#123456');
  $('element-icon').value='circle';
  $('element-form').onsubmit({preventDefault(){}});
  assert.equal(node.color,'#123456');assert.equal(node.switchColor,'#2288cc');
});

test('icon categories restrict groups to circle and hosts to switch while free elements keep both',()=>{
  const {$,context,run}=setup();
  const slotId=context.configuredSlots[0].id;
  context.configuredSwitchColor='#2288cc';
  $('type').value='group';run('syncElementIcons()');
  assert.equal($('element-icon').value,'circle');
  assert.equal($('element-icon').options[1].hidden,true);
  $('type').value='host';run('syncElementIcons()');
  assert.equal($('element-icon').value,'switch');
  assert.equal($('element-icon').options[0].hidden,true);
  $('type').value='';run('syncElementIcons()');
  assert.ok($('element-icon').options.every(option=>!option.disabled&&!option.hidden));
  assert.equal(context.configuredSlots[0].id,slotId);
  assert.equal(context.configuredSwitchColor,'#2288cc');
});

test('loading interfaces preserves slots and displays the server diagnostic as text', async () => {
  const {$,context}=setup();
  $('type').value='host'; $('group').value='42'; $('element-dialog').open=true;
  const states=[];
  context.renderSwitchSlots=()=>states.push(context.loadingInterfaces);
  $('interface-load-status').setAttribute=function(name,value){this[name]=value;};
  context.TableState = { error(id, message) { context.discoveryError = message; } };
  let respond;
  context.fetch=()=>new Promise(resolve=>{respond=resolve;});
  vm.runInContext(source.slice(source.indexOf("$('load-switch-interfaces').onclick ="),
    source.indexOf("$('add-switch-slot').onclick =")),context);
  const pending=$('load-switch-interfaces').onclick();
  assert.equal(context.loadingInterfaces,true);
  assert.equal($('load-switch-interfaces').disabled,true);
  assert.deepEqual(states,[true]);
  respond({ok:false,status:502,json:async()=>({message:'SSH conectado, mas o formato de show interface link não foi reconhecido.'})});
  await pending;
  assert.deepEqual(states,[true,false]);
  assert.equal(context.configuredSlots.length,1);
  assert.equal($('interface-load-status').role,'alert');
  assert.equal(context.discoveryError,'SSH conectado, mas o formato de show interface link não foi reconhecido.');
});

test('group submap selection is restored and persisted; changing type clears it',()=>{
  const {$,context,node,run}=setup();
  context.maps=[{id:'child',layout:{name:'Filial'}}];
  node.groupId='group';node.subMapId='child';
  run('openElement(current.layout.nodes[0])');
  assert.equal($('sub-map-field').hidden,false);
  assert.equal($('sub-map').value,'child');
  $('group').value='group';
  $('element-form').onsubmit({preventDefault(){}});
  assert.equal(node.subMapId,'child');
  $('type').value='';
  $('element-form').onsubmit({preventDefault(){}});
  assert.equal(node.subMapId,null);
});
test('submap selector excludes occupied maps, the current map and its ancestors',()=>{
  const {$,context,run}=setup();
  context.current.id='current';
  context.configuredNodeId='node';
  context.mapAncestors=()=>['root'];
  context.maps=[
    {id:'current',layout:context.current.layout},
    {id:'root',layout:{name:'Raiz',nodes:[{id:'parent-node',groupId:'g',subMapId:'current'}]}},
    {id:'used',layout:{name:'Ocupado',nodes:[]}},
    {id:'other',layout:{name:'Outro',nodes:[{id:'node',groupId:'g',subMapId:'used'}]}},
    {id:'available',layout:{name:'Disponível',nodes:[]}}
  ];
  $('type').value='group';
  run("syncSubMap('')");
  assert.deepEqual(Array.from($('sub-map').options, option=>option.value),['','other','available']);
});

for (const [label, response, message] of [
  ['HTML gateway failure', { ok: false, status: 502, json: async () => { throw new SyntaxError('HTML'); } }, /HTTP 502/],
  ['login redirect', { ok: true, status: 200, redirected: true }, /redirecionada/],
  ['invalid success body', { ok: true, status: 200, json: async () => ({}) }, /resposta inválida/],
]) {
  test(`discovery identifies ${label} without reporting an equipment connection failure`, async () => {
    const { $, context } = setup();
    $('type').value = 'host'; $('group').value = '42'; $('element-dialog').open = true;
    $('interface-load-status').setAttribute = function (key, value) { this[key] = value; };
    context.TableState = { error(id, text) { context.discoveryError = text; } };
    context.fetch = async () => response;
    vm.runInContext(source.slice(source.indexOf("$('load-switch-interfaces').onclick ="), source.indexOf("$('add-switch-slot').onclick =")), context);
    await $('load-switch-interfaces').onclick();
    assert.match(context.discoveryError, message);
    assert.equal(context.configuredSlots.length, 1);
    assert.equal(context.loadingInterfaces, false);
  });
}
test('discovery distinguishes a browser port assembly error from an SSH failure', async () => {
  const { $, context } = setup();
  $('type').value = 'host'; $('group').value = '42'; $('element-dialog').open = true;
  $('interface-load-status').setAttribute = function (key, value) { this[key] = value; };
  context.TableState = { error(id, text) { context.discoveryError = text; } };
  context.fetch = async () => ({ ok: true, status: 200, json: async () => [{ name: 'ether1' }] });
  context.MapSwitch = { ...MapSwitch, importInterfaces() { throw new TypeError('crypto.randomUUID is not a function'); } };
  vm.runInContext(source.slice(source.indexOf("$('load-switch-interfaces').onclick ="), source.indexOf("$('add-switch-slot').onclick =")), context);
  await $('load-switch-interfaces').onclick();
  assert.match(context.discoveryError, /recebidas.*navegador/);
  assert.equal(context.configuredSlots.length, 1);
});

test('telecom site is available exclusively for host groups and resets when the binding changes', () => {
  const {$, run} = setup();
  const template = fs.readFileSync(require('node:path').join(__dirname, '../../main/resources/templates/maps.html'), 'utf8');
  const option = template.match(/<option value="telecom-site" data-bindings="([^"]+)"/);
  assert.ok(option);
  $('element-icon').options.push({value: 'telecom-site', dataset: {bindings: option[1]}});
  $('type').value = 'group';
  $('element-icon').value = 'telecom-site';
  run('syncElementIcons()');
  assert.equal($('element-icon').value, 'telecom-site');
  assert.equal($('element-icon').options.at(-1).hidden, false);
  for (const binding of ['host', '']) {
    $('type').value = binding;
    $('element-icon').value = 'telecom-site';
    run('syncElementIcons()');
    assert.equal($('element-icon').options.at(-1).disabled, true);
    assert.notEqual($('element-icon').value, 'telecom-site');
  }
});

test('telecom site transforms artwork while keeping its label horizontal', () => {
  const svg = (tag, attributes) => ({tag, attributes, children: [], append(...items) {this.children.push(...items);}});
  const group = svg('g', {});
  const context = vm.createContext({svg, MapSwitch, group, editing: false});
  vm.runInContext(source.slice(source.indexOf('function drawTelecomSite('), source.indexOf('function alignDraggedNode(')), context);
  vm.runInContext("drawTelecomSite(group, 'POP', 'BELOW', 'green', false, {scale: 2, rotation: 90, flipX: true})", context);
  assert.equal(group.children[0].attributes.transform, 'rotate(90) scale(-2 2)');
  assert.equal(group.children[0].children[0].tag, 'image');
  assert.equal(group.children[1].tag, 'text');
  assert.equal(group.children[1].textContent, 'POP');
  assert.ok(Math.abs(group.children[1].attributes.y - 129) < 1e-9);
});


test('telecom site hides the status dot during editing and preserves it in viewing mode', () => {
  const svg = (tag, attributes) => ({tag, attributes, children: [], append(...items) {this.children.push(...items);}});
  const context = vm.createContext({svg, MapSwitch, editing: true});
  vm.runInContext(source.slice(source.indexOf('function drawTelecomSite('), source.indexOf('function alignDraggedNode(')), context);
  for (const editing of [true, false]) {
    context.editing = editing;
    context.group = svg('g', {});
    vm.runInContext("drawTelecomSite(group, 'POP', 'BELOW', 'green')", context);
    assert.equal(context.group.children[0].children.some(child => child.tag === 'circle'), !editing);
  }
});


test('group binding hides and disables interface controls without discarding the draft', () => {
  const {$, context, run} = setup();
  vm.runInContext(source.slice(source.indexOf('function syncInterfaceControls('), source.indexOf('function drawPreview(')), context);
  $('interfaces-enabled').checked = true;
  $('type').value = 'group';
  assert.equal(run('syncInterfaceControls()'), false);
  assert.equal($('element-interfaces').hidden, true);
  assert.equal($('interfaces-enabled').disabled, true);
  assert.equal($('element-slot-actions').hidden, true);
  assert.equal($('switch-config').hidden, true);
  assert.equal($('switch-config').disabled, true);
  for (const binding of ['host', '']) {
    $('type').value = binding;
    assert.equal(run('syncInterfaceControls()'), true);
    assert.equal($('element-interfaces').hidden, false);
    assert.equal($('interfaces-enabled').disabled, false);
    assert.equal($('element-slot-actions').hidden, false);
    assert.equal($('switch-config').hidden, false);
  }
  $('interfaces-enabled').checked = false;
  assert.equal(run('syncInterfaceControls()'), false);
  assert.equal($('element-interfaces').hidden, false);
  assert.equal($('element-slot-actions').hidden, true);
  assert.equal($('switch-config').disabled, true);
});

test('Mimosa Zabbix discovery keeps both wireless candidates for explicit review', async () => {
  const {$, context}=setup();
  $('type').value='host';$('group').value='42';$('element-dialog').open=true;
  $('interface-load-status').setAttribute=function(key,value){this[key]=value;};
  context.fetch=async url=>{
    assert.equal(url,'/api/maps/connections/discover/42');
    return {ok:true,status:200,json:async()=>['eth1_emac1','wifi0','wlan0'].map(name=>({name,source:'zabbix'}))};
  };
  vm.runInContext(source.slice(source.indexOf("$('load-switch-interfaces').onclick ="),source.indexOf("$('add-switch-slot').onclick =")),context);
  await $('load-switch-interfaces').onclick();
  assert.equal(context.configuredPorts.length,3);
  assert.equal(context.configuredPorts.filter(p=>p.connector==='wireless').length,2);
  assert.match($('interface-load-status').textContent,/mais de uma interface Wireless/);
  assert.match($('interface-load-status').textContent,/nenhuma interface foi escolhida automaticamente/);
});
