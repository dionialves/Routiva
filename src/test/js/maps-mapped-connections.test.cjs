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
    setInterval(){}, Date, Map, Set, Number, JSON, calls:0,
    fetch:async () => { context.calls++; return {ok:true,json:async()=>[]}; }
  });
  vm.runInContext(source.slice(source.indexOf('let connectionDefinitions =')), context);
  return {context, run:code=>vm.runInContext(code,context)};
}
test('mapped collection deduplicates links and pauses in editing or hidden tabs', async () => {
  const {context,run} = setup();
  run("current = {layout:{links:[{mappedConnectionId:'c1'},{mappedConnectionId:'c1'},{}]}};");
  context.fetch = async (url, options) => {
    context.calls++; assert.deepEqual(JSON.parse(options.body), ['c1']);
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
  assert.match(title, /TX A → B: 2 Mbps/);
  assert.match(title, /RX B → A: 1 Mbps/);
  assert.match(title, /ether2: Leitura desatualizada/);
});
