const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../../main/resources/static/maps.js'), 'utf8');
function setup() {
  const elements = new Map();
  const $ = id => {
    if (!elements.has(id)) elements.set(id, {value:'',open:false,focus(){},replaceChildren(){},
      showModal(){this.open=true;},close(){this.open=false;this.onclose?.();}});
    return elements.get(id);
  };
  const link = {from:'a',to:'b',fromPort:'r1',style:'corners',points:[{x:10,y:80}]};
  const context = vm.createContext({$, link, editing:true, pendingLink:null, selectedLink:null, selected:null,
    savingConnection:false, loadConnectionInterfaces(){}, lastConnectionClick:null,selection:new Set(),checkpoints:0,draw(){},svg:()=>({}),
    current:{layout:{nodes:[{id:'a',hostId:'h1',name:'SW',ports:[{id:'r1',type:'rj45'}]},
      {id:'b',groupId:'g1',name:'Grupo'}],links:[link]}},
    inventory:[{id:'h1',name:'Switch Core'}],groups:[{id:'g1',name:'Datacenter'}],
    MapLinks:require('../../main/resources/static/map-links.js'),
    MapSwitch:require('../../main/resources/static/map-switch.js')
  });
  vm.runInContext('function checkpoint(){checkpoints++;} function linkRoute(){return [{x:0,y:0},{x:200,y:0}];}', context);
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
  $('link-dialog-style').value='curved';
  $('cancel-link').onclick();
  assert.equal(context.link.lineStyle,undefined);
  assert.equal(context.checkpoints,0);
});
test('changing line appearance resets waypoints and supports undo', () => {
  const {$,run,context} = setup();
  run('openLinkDialog(link)');
  $('link-dialog-style').value='curved';
  $('link-form').onsubmit({preventDefault(){}});
  assert.equal(context.link.lineStyle,'curved');
  assert.equal(context.link.style,'corners');
  assert.equal(context.link.points.length,0);
  assert.equal(context.checkpoints,1);
  assert.equal($('link-dialog').open,false);
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
  assert.equal($('link-from-interface').textContent, 'sfp-sfpplus2 · ID h1');
  assert.equal($('link-to-interface').textContent, 'ether3 · ID g1');
  assert.match($('connection-host-a').textContent, /Switch Core/);
  assert.equal($('link-from-capacity').hidden, true);
  assert.equal(context.checkpoints, 0);
});
test('header close dismisses the dialog without applying draft appearance', () => {
  const {$, run, context} = setup();
  run('openLinkDialog(link)');
  $('link-dialog-style').value = 'curved';
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
