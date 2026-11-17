const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync(require('node:path').join(__dirname,'../../main/resources/static/maps.js'),'utf8');
function setup() {
  const context=vm.createContext({pingStates:new Map(),pingGeneration:1,pingTimer:undefined,
    current:{layout:{nodes:[{icon:'switch',hostId:'h'},{icon:'switch',hostId:'h'}]}},editing:false,
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
