const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync(require('node:path').join(__dirname,'../../main/resources/static/maps.js'),'utf8');
function setup() {
  const context=vm.createContext({pingStates:new Map(),pingGeneration:1,pingTimer:undefined,
    inventory:[],current:{layout:{nodes:[{icon:'switch',hostId:'h'},{icon:'switch',hostId:'h'}]}},editing:false,
    MapSwitch:require('../../main/resources/static/map-switch.js'),
    $:()=>({querySelectorAll:()=>[]}),clearTimeout(){},setTimeout(fn,delay){context.delay=delay;return 1;},
    api:async()=>[{id:'h',state:'online',measuredAt:Date.now()}]});
  vm.runInContext(source.slice(source.indexOf('function pingStateFor('),source.indexOf('function openMap(')),context);
  return {context,run:code=>vm.runInContext(code,context)};
}
test('ping refresh is independent from incident view and stale samples never remain green',async()=>{
  const {context,run}=setup();
  await run('refreshPingState(1)');
  assert.equal(run("pingStateFor('h')"),'online');
  assert.equal(context.delay,2000);
  context.pingStates.set('h',{state:'online',measuredAt:Date.now()-20000});
  assert.equal(run("pingStateFor('h')"),'unknown');
});
test('collection API failures clear connectivity and late results cannot update a different map',async()=>{
  const {context,run}=setup();
  await run('refreshPingState(1)');
  context.api=async()=>{throw Error('unavailable');};
  await run('refreshPingState(1)');
  assert.equal(context.pingStates.size,0);
  context.api=async()=>{context.pingGeneration++;return [{id:'h',state:'online',measuredAt:Date.now()}];};
  await run('refreshPingState(1)');
  assert.equal(context.pingStates.size,0);
});
test('edit mode never requests ping status',async()=>{
  const {context,run}=setup();context.editing=true;
  context.api=async()=>{throw Error('should not request');};
  await run('refreshPingState(1)');assert.equal(context.delay,undefined);
});

test('server LEDs use fresh ping latency and distinguish timeout, collection errors and edit mode',()=>{
  const {context,run}=setup();
  context.node={hostId:'h'};
  for (const [state,latency,color] of [['online',2,'#58bd83'],['online',15,'#fced4e'],['online',80,'#fced4e'],['offline',null,'#ff0000'],['unknown',null,'#687486']]) {
    context.pingStates.set('h',{state,latencyMs:latency,measuredAt:Date.now()});
    assert.equal(run('serverStatusFor(node).color'),color);
  }
  context.pingStates.set('h',{state:'online',latencyMs:2,measuredAt:Date.now()-11000});
  assert.equal(run('serverStatusFor(node).color'),'#687486');
  context.editing=true;
  context.pingStates.set('h',{state:'online',latencyMs:2,measuredAt:Date.now()});
  assert.equal(run('serverStatusFor(node).color'),'#687486');
});
test('server icons participate in periodic ping requests, including group bindings',async()=>{
  const {context,run}=setup();
  context.current.layout.nodes=[{icon:'tower-server',hostId:'tower'},{icon:'vm-server',groupId:'group'}];
  context.api=async(url,body)=>{context.request=body;return [];};
  await run('refreshPingState(1)');
  assert.deepEqual(Array.from(context.request.hosts),['tower']);
  assert.deepEqual(Array.from(context.request.groups),['group']);
  assert.equal(context.delay,2000);
});
test('server drawing replaces the VM light and puts the tower light in its upper right corner',()=>{
  const MapSwitch=require('../../main/resources/static/map-switch.js');
  const svg=(tag,attrs)=>({tag,attrs,children:[],append(...children){this.children.push(...children);}});
  const context=vm.createContext({MapSwitch,svg,editing:false,serverLedSequence:0,
    serverStatusFor:()=>({color:'#ff0000',label:'Offline (ping)'})});
  vm.runInContext(source.slice(source.indexOf('function drawTelecomSite('),source.indexOf('function alignDraggedNode(')),context);
  for(const icon of ['vm-server','tower-server']) {
    context.root=svg('g',{});context.node={id:'server',icon};
    vm.runInContext("drawTelecomSite(root,'Server','BELOW','#58bd83',false,node)",context);
    const parts=context.root.children[0].children;
    const leds=parts.filter(part=>part.attrs.class==='server-status-led');
    assert.equal(leds.length,1);assert.equal(leds[0].attrs.fill,'#ff0000');
    if(icon==='vm-server') {
      assert.ok(parts.some(part=>part.tag==='image' && part.attrs['clip-path']));
      assert.equal(leds[0].attrs.cx,-67.3);assert.equal(leds[0].attrs.cy,7.9);
    } else {
      assert.ok(leds[0].attrs.cx>0);assert.ok(leds[0].attrs.cy<0);
    }
  }
});
