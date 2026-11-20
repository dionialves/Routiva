const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync(require('node:path').join(__dirname, '../../main/resources/static/maps.js'), 'utf8');
function setup() {
  const controls = new Map();
  const listeners = {};
  const context = vm.createContext({
    $: id => { if (!controls.has(id)) controls.set(id, {}); return controls.get(id); },
    current:null, editing:false, inventory:[], MapTraffic:require('../../main/resources/static/map-traffic.js'), document:{hidden:false, querySelectorAll:()=>[], addEventListener(name, handler){ listeners[name] = handler; }},
    setInterval(){}, setTimeout(){}, Date, Map, Set, Number, JSON, calls:0,
    fetch:async () => { context.calls++; return {ok:true,json:async()=>[]}; }
  });
  vm.runInContext(source.slice(source.indexOf('let connectionDefinitions =')), context);
  return {context, listeners, run:code=>vm.runInContext(code,context)};
}
test('one deduplicated batch per map and a 60 second interval', async () => {
  const {context,run} = setup();
  const ids = Array.from({length:125}, (_, i) => `c${i}`);
  context.current = {id:'map',layout:{links:[...ids,ids[0]].map(mappedConnectionId=>({mappedConnectionId}))}};
  context.fetch = async (url, options) => {
    context.calls++;
    assert.equal(url, '/api/maps/map/connections/readings');
    assert.deepEqual(JSON.parse(options.body), ids);
    return {ok:true,json:async()=>ids.map(id=>({id}))};
  };
  await run('refreshMappedConnections()');
  assert.equal(context.calls,1);
  assert.equal(run('connectionReadings.size'),125);
  assert.match(source,/setInterval\(refreshMappedConnections, 60000\)/);
  context.editing=true; await run('refreshMappedConnections()');
  context.editing=false; context.document.hidden=true; await run('refreshMappedConnections()');
  assert.equal(context.calls,1);
});
test('pending batches are shared and failures preserve the last readings', async () => {
  const {context,run} = setup();
  run("current={id:'map',layout:{links:[{mappedConnectionId:'c'}]}};connectionReadings.set('c',{marker:'old'})");
  let reject;
  context.fetch=()=>{context.calls++;return new Promise((_,no)=>{reject=no;});};
  const first=run('refreshMappedConnections()');
  const second=run('refreshMappedConnections()');
  assert.equal(context.calls,1);
  reject(new Error('offline'));
  assert.equal(await first,false); assert.equal(await second,false);
  assert.equal(run("connectionReadings.get('c').marker"),'old');
  assert.equal(run("connectionFailures.has('c')"),true);
});
test('manual refresh explicitly bypasses the cache without warmup requests', async () => {
  const {context,run} = setup();
  run("current={id:'map',layout:{links:[{mappedConnectionId:'c'}]}}");
  context.setTimeout=()=>assert.fail('no warmup timer');
  context.fetch=async url=>{
    assert.equal(url,'/api/maps/map/connections/readings?force=true');
    return {ok:true,json:async()=>[{id:'c',a:{up:true,txBps:null}}]};
  };
  assert.equal(await run('refreshMappedConnections(true)'),true);
});
test('navigation discards late responses from a previous map', async () => {
  const {context,run}=setup();
  run("current={id:'old',layout:{links:[{mappedConnectionId:'c'}]}}");
  let finish;context.fetch=()=>new Promise(resolve=>{finish=resolve;});
  const first=run('refreshMappedConnections()');
  run("current={id:'new',layout:{links:[]}}");
  finish({ok:true,json:async()=>[{id:'c'}]});await first;
  assert.equal(run('connectionReadings.size'),0);
});
test('manual refresh after an ordinary pending poll performs a fresh query', async () => {
  const {context,run}=setup();
  run("current={id:'map',layout:{links:[{mappedConnectionId:'c'}]}}");
  let finish;const urls=[];
  context.fetch=url=>{urls.push(url);return urls.length===1?new Promise(resolve=>{finish=resolve;}):Promise.resolve({ok:true,json:async()=>[]});};
  const poll=run('refreshMappedConnections()');
  const manual=run('refreshMappedConnections(true)');
  finish({ok:true,json:async()=>[]});await poll;await manual;
  assert.deepEqual(urls,['/api/maps/map/connections/readings','/api/maps/map/connections/readings?force=true']);
});

test('unchanged versions preserve readings and renew the visible snapshot', async () => {
  const {context,run}=setup();
  run("current={id:'map',layout:{links:[{mappedConnectionId:'c'}]}}");
  let calls=0;
  context.fetch=async(url,options)=>{
    calls++;
    assert.equal(options.headers['X-Map-Readings-Version'],calls===1?'':'v1');
    return {ok:true,json:async()=>calls===1?{version:'v1',unchanged:false,readings:[{id:'c',marker:'retained'}]}:{version:'v1',unchanged:true,readings:[]}};
  };
  await run('refreshMappedConnections()');await run('refreshMappedConnections()');
  assert.equal(run("connectionReadings.get('c').marker"),'retained');
});

test('hidden maps preserve their snapshot and resume immediately with updated readings', async () => {
  const {context, listeners, run} = setup();
  let now = 10000;
  context.Date = {now: () => now};
  run("current = {id:'map1',layout:{links:[{mappedConnectionId:'c1'}]}}; connectionReadings.set('c1', {a:{up:true,measuredAt:10000,txBps:500,rxBps:100}})");
  context.document.hidden = true;
  listeners.visibilitychange();
  now = 100000;
  await run('refreshMappedConnections()');
  assert.equal(context.calls, 0);
  assert.equal(run("MapTraffic.state(connectionReadings.get('c1'), connectionDisplayTime('c1')).tx"), 500);
  let finish;
  context.fetch = () => { context.calls++; return new Promise(resolve => { finish = resolve; }); };
  context.document.hidden = false;
  listeners.visibilitychange();
  assert.equal(context.calls, 1);
  assert.equal(run("connectionDisplayTime('c1')"), 10000);
  finish({ok:true,json:async()=>[{id:'c1',a:{up:false,measuredAt:now,txBps:0,rxBps:0}}]});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(run("connectionDisplayTime('c1')"), now);
  assert.equal(run("connectionReadings.get('c1').a.up"), false);
});

test('a request completing while hidden does not replace the visible snapshot', async () => {
  const {context, listeners, run} = setup();
  run("current = {id:'map1',layout:{links:[{mappedConnectionId:'c1'}]}}; connectionReadings.set('c1', {marker:'visible'})");
  let finish;
  context.fetch = () => new Promise(resolve => { finish = resolve; });
  const pending = run('refreshMappedConnections()');
  context.document.hidden = true;
  listeners.visibilitychange();
  finish({ok:true,json:async()=>[{id:'c1',marker:'hidden'}]});
  await pending;
  assert.equal(run("connectionReadings.get('c1').marker"), 'visible');
});


test('mapped traffic from a group keeps the persisted A to B direction', () => {
  const {context,run}=setup();
  let reversed;
  context.svg=()=>({});
  context.MapTraffic={render(...args){reversed=args[7];}};
  context.layer={trafficContext:{link:{from:'group',mappedConnectionId:'c'},nodes:[{id:'group',groupId:'g'}]}};
  run("connectionDefinitions=[{id:'c',hostA:'member',hostB:'other'}];connectionReadings.set('c',{hostA:'member'});updateTrafficLayer(layer)");
  assert.equal(reversed,false);
});
