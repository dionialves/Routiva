const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync(require('node:path').join(__dirname, '../../main/resources/static/maps.js'), 'utf8');
function setup() {
  const controls = new Map();
  const context = vm.createContext({
    $: id => { if (!controls.has(id)) controls.set(id, {}); return controls.get(id); },
    current:null, editing:false, inventory:[], MapTraffic:require('../../main/resources/static/map-traffic.js'), document:{hidden:false, querySelectorAll:()=>[], addEventListener(){}},
    setInterval(){}, setTimeout(){}, Date, Map, Set, Number, JSON, calls:0,
    fetch:async () => { context.calls++; return {ok:true,json:async()=>[]}; }
  });
  vm.runInContext(source.slice(source.indexOf('let connectionDefinitions =')), context);
  return {context, run:code=>vm.runInContext(code,context)};
}
test('mapped collection deduplicates links and pauses in editing or hidden tabs', async () => {
  const {context,run} = setup();
  run("current = {id:'map1',layout:{links:[{mappedConnectionId:'c1'},{mappedConnectionId:'c1'},{}]}};");
  context.fetch = async (url, options) => {
    context.calls++; assert.equal(url, '/api/maps/map1/connections/readings'); assert.deepEqual(JSON.parse(options.body), ['c1']);
    return {ok:true, json:async()=>[]};
  };
  await run('refreshMappedConnections()'); assert.equal(context.calls, 1);
  context.editing = true; await run('refreshMappedConnections()');
  context.editing = false; context.document.hidden = true; await run('refreshMappedConnections()');
  assert.equal(context.calls, 1);
});
test('mapped collection never overlaps pending requests and exposes API failures', async () => {
  const {context,run} = setup();
  run("current = {layout:{links:[{mappedConnectionId:'c1'}]}};");
  let reject;
  context.fetch = () => { context.calls++; return new Promise((_, no) => { reject = no; }); };
  const first = run('refreshMappedConnections()');
  await run('refreshMappedConnections()'); assert.equal(context.calls, 1);
  reject(new Error('offline')); await first;
  assert.match(run("mappedConnectionTitle('c1')"), /Consulta indisponível/);
});
test('tooltips show endpoint rates and do not present old readings as UP', () => {
  const {run} = setup();
  run("connectionReadings.set('c1', {a:{name:'ether1',up:true,rxBps:'1000000',txBps:'2000000',measuredAt:Date.now()}, b:{name:'ether2',up:true,measuredAt:Date.now()-31000}})");
  const title = run("mappedConnectionTitle('c1')");
  assert.match(title, /ether1: UP/);
  assert.match(title, /Download: 2 Mbps/);
  assert.match(title, /Upload: 1 Mbps/);
  assert.match(title, /ether2: Leitura desatualizada/);
});

test('opening a map primes traffic with one early follow-up and skips it after navigation', async () => {
  const {context, run} = setup();
  const timers = [];
  context.setTimeout = (callback, delay) => timers.push({callback, delay});
  run("current = {layout:{links:[{mappedConnectionId:'c1'}]}};");
  context.fetch = async () => {
    context.calls++;
    return {ok:true,json:async()=>[{id:'c1',a:{up:true,txBps:null,rxBps:null}}]};
  };
  await run('refreshMappedConnections(true)');
  assert.equal(timers.length, 1);
  assert.equal(timers[0].delay, 1200);
  await timers[0].callback();
  assert.equal(context.calls, 2);
  assert.equal(timers.length, 1);
  await run('refreshMappedConnections(true)');
  context.editing = true;
  await timers[1].callback();
  assert.equal(context.calls, 3);
  context.editing = false;
  context.current = {layout:{links:[]}};
  await timers[1].callback();
  assert.equal(context.calls, 3);
});
test('opening a map with measured rates does not schedule an early follow-up', async () => {
  const {context,run} = setup();
  context.setTimeout = () => assert.fail('unexpected follow-up');
  run("current = {layout:{links:[{mappedConnectionId:'c1'}]}};");
  context.fetch = async () => ({ok:true,json:async()=>[{id:'c1',a:{up:true,txBps:'0',rxBps:'0'}}]});
  await run('refreshMappedConnections(true)');
});

test('connections receive independent results and keep polling while a different link is pending', async () => {
  const {context, run} = setup();
  run("current = {id:'map1',layout:{links:[{mappedConnectionId:'slow'},{mappedConnectionId:'fast'}]}};");
  let finishSlow;
  const calls = [];
  context.fetch = (url, options) => {
    const [id] = JSON.parse(options.body);
    calls.push(id);
    if (id === 'slow') return new Promise(resolve => { finishSlow = resolve; });
    return Promise.resolve({ok:true, json:async()=>[{id, a:{up:true, measuredAt:Date.now()}}]});
  };
  const first = run('refreshMappedConnections()');
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(calls, ['slow', 'fast']);
  assert.equal(run("connectionReadings.has('fast')"), true);
  assert.equal(run("connectionReadings.has('slow')"), false);
  await run('refreshMappedConnections()');
  assert.deepEqual(calls, ['slow', 'fast', 'fast']);
  finishSlow({ok:false,json:async()=>({message:'offline'})});
  await first;
  assert.equal(run("connectionReadings.has('fast')"), true);
  assert.equal(run("connectionFailures.has('fast')"), false);
  assert.equal(run("connectionFailures.has('slow')"), true);
});

test('a response from a previous map cannot overwrite the current map', async () => {
  const {context, run} = setup();
  run("current = {id:'old',layout:{links:[{mappedConnectionId:'c1'}]}};");
  let finishOld;
  context.fetch = () => new Promise(resolve => { finishOld = resolve; });
  const old = run('refreshMappedConnections()');
  run("current = {id:'new',layout:{links:[{mappedConnectionId:'c1'}]}};");
  context.fetch = async () => ({ok:true,json:async()=>[{id:'c1', marker:'new'}]});
  await run('refreshMappedConnections()');
  finishOld({ok:true,json:async()=>[{id:'c1',marker:'old'}]});
  await old;
  assert.equal(run("connectionReadings.get('c1').marker"), 'new');
});

test('all connections get their own request without a five-link or batch limit', async () => {
  const {context, run} = setup();
  const ids = Array.from({length:125}, (_, i) => `c${i}`);
  context.current = {id:'large',layout:{links:ids.map(mappedConnectionId => ({mappedConnectionId}))}};
  const requested = [];
  context.fetch = async (url, options) => {
    const body = JSON.parse(options.body);
    assert.equal(body.length, 1);
    requested.push(body[0]);
    return {ok:true,json:async()=>[{id:body[0]}]};
  };
  await run('refreshMappedConnections()');
  assert.deepEqual(requested, ids);
  assert.equal(run('connectionReadings.size'), 125);
});
