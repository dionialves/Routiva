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
      showModal(){this.open=true;},close(){this.open=false;},querySelector(){return {scrollTop:0,click(){}};}});
    return fields.get(id);
  };
  $('element-icon').options=[{value:'circle',dataset:{bindings:'group free'}},{value:'switch',dataset:{bindings:'host free'}}];
  $('element-name').value='Switch';$('element-icon').value='switch';$('label-position').value='BELOW';
  $('interfaces-enabled').checked=true;
  const context=vm.createContext({$,MapSwitch,current:{layout:{nodes:[node],links:[{from:'node',to:'other',fromPort:ports[0].id}]}},
    configuredSlots:slots.map(s=>({...s})),configuredPorts:ports,configuredSwitchColor:null,pendingNode:null,selected:'node',selectedLink:null,
    selection:new Set(),tool:'select',timer:null,generation:0,checkpoint(){},refresh(){},clearTimeout(){},
    draw(){},drawPreview(){},populateBinding(){},renderSwitchSlots(){},window:{elementIconPicker:{sync(){}}},
    confirm(){throw Error('No connections should be removed');}});
  vm.runInContext('function switchDraft(){return MapSwitch.portsForSlots(configuredPorts,configuredSlots);}',context);
  vm.runInContext(source.slice(source.indexOf("$('element-form').onsubmit ="),source.indexOf('function deleteSelection(')),context);
  vm.runInContext(source.slice(source.indexOf('function openElement('),source.indexOf('window.elementIconPicker =')),context);
  return {$,context,node,run:code=>vm.runInContext(code,context)};
}
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
