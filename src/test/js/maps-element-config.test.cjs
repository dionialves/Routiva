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
  assert.equal($('interface-load-status').textContent,'SSH conectado, mas o formato de show interface link não foi reconhecido.');
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
    context.fetch = async () => response;
    vm.runInContext(source.slice(source.indexOf("$('load-switch-interfaces').onclick ="), source.indexOf("$('add-switch-slot').onclick =")), context);
    await $('load-switch-interfaces').onclick();
    assert.match($('interface-load-status').textContent, message);
    assert.equal(context.configuredSlots.length, 1);
    assert.equal(context.loadingInterfaces, false);
  });
}
test('discovery distinguishes a browser port assembly error from an SSH failure', async () => {
  const { $, context } = setup();
  $('type').value = 'host'; $('group').value = '42'; $('element-dialog').open = true;
  $('interface-load-status').setAttribute = function (key, value) { this[key] = value; };
  context.fetch = async () => ({ ok: true, status: 200, json: async () => [{ name: 'ether1' }] });
  context.MapSwitch = { ...MapSwitch, importInterfaces() { throw new TypeError('crypto.randomUUID is not a function'); } };
  vm.runInContext(source.slice(source.indexOf("$('load-switch-interfaces').onclick ="), source.indexOf("$('add-switch-slot').onclick =")), context);
  await $('load-switch-interfaces').onclick();
  assert.match($('interface-load-status').textContent, /recebidas.*navegador/);
  assert.equal(context.configuredSlots.length, 1);
});
