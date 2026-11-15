const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/maps.js'), 'utf8');

test('metric summary uses distinct host and group values, units and selected metric classification', () => {
  const context = vm.createContext({
    colors: ['gray'], URLSearchParams,
    inventory: [{id:'a', groups:[{id:'g1'}, {id:'g2'}]}],
    $: () => ({value:'60', selectedOptions: [{textContent:'Última 1 hora'}]}),
  });
  vm.runInContext(source.slice(source.indexOf('let mapView ='), source.indexOf('function changeMapQuery(')), context);
  vm.runInContext(`mapView = 'latency'; metricStates.set('host:a', {latency:21, latencyState:'CRITICO'});
    metricStates.set('group:a', {latency:10, latencyState:'NORMAL', loss:0.5, lossState:'ATENCAO'});`, context);
  let result = vm.runInContext("metricSummary({hostId:'a'})", context);
  assert.equal(result.detailsUrl, '/disponibilidade?minutes=60&groupId=g1%2Cg2');
  assert.equal(result.color, '#ff0000');
  assert.equal(result.formatted, '21 ms');
  result = vm.runInContext("metricSummary({groupId:'a'})", context);
  assert.equal(result.color, '#58bd83');
  assert.equal(result.formatted, '10 ms');
  result = vm.runInContext("mapView = 'loss'; metricSummary({groupId:'a'})", context);
  assert.equal(result.color, '#fced4e');
  assert.equal(result.formatted, '0,5%');
  assert.equal(result.detailsUrl, '/disponibilidade?minutes=60&groupId=a');
  assert.equal(vm.runInContext("metricSummary({hostId:'missing'}).state", context), 'Sem dados');
  assert.equal(vm.runInContext("mapView = 'incidents'; metricSummary({hostId:'a'})", context), null);
});

function refreshContext() {
  const pending = [];
  const controls = {'refresh-map': {setAttribute() {}}, 'status': {}, 'map-period': {value:'60'}};
  const context = vm.createContext({
    $: id => controls[id], current: {layout:{nodes:[{hostId:'a'}, {groupId:'g'}]}}, editing:false,
    generation:1, timer:0, latencySummaries:new Map(), mapView:'latency', metricStates:new Map(), summaryUpdatedAt:0,
    api: (path, body) => path === '/latency-summaries' ? Promise.resolve({}) : new Promise((resolve, reject) => pending.push({path, body, resolve, reject})),
    clearTimeout() {}, setTimeout: () => 1, draw() {}, updateMapOverview() {}, popSummary:{render() {}},
  });
  vm.runInContext(source.slice(source.indexOf('function setRefreshBusy('), source.indexOf('function openMap(')), context);
  return {context, pending, controls};
}

test('metric refresh batches targets, ignores obsolete results and preserves data on failure', async () => {
  const {context, pending, controls} = refreshContext();
  const old = vm.runInContext('refresh(1)', context);
  assert.equal(pending[0].path, '/metrics');
  assert.equal(pending[0].body.minutes, 60);
  assert.deepEqual(Array.from(pending[0].body.groups), ['g']);
  const current = vm.runInContext("mapView = 'loss'; generation = 2; refresh(2)", context);
  pending[1].resolve({elements:[{type:'host', id:'a', loss:1}], builtAt:Date.now(), from:0, to:Date.now()});
  await current;
  pending[0].resolve({elements:[{type:'host', id:'a', loss:99}], builtAt:1, from:0, to:1});
  await old;
  assert.equal(context.metricStates.get('host:a').loss, 1);
  const failed = vm.runInContext('refresh(2)', context);
  pending[2].reject(new Error('offline'));
  await failed;
  assert.equal(context.metricStates.get('host:a').loss, 1);
  assert.match(controls.status.textContent, /indisponível/);
  assert.equal(controls['refresh-map'].disabled, false);
});

test('period defaults to 24 hours and restores a valid browser preference after reload', () => {
  const storage = new Map();
  function load() {
    const control = {value:'', options:[{value:'60'}, {value:'1440'}, {value:'2880'}]};
    const context = vm.createContext({
      $: () => control,
      localStorage: {getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value)},
    });
    vm.runInContext(source.slice(source.indexOf('const mapPeriodStorageKey'), source.indexOf('function changeMapQuery(')), context);
    vm.runInContext('restoreMapPeriod()', context);
    return {context, control};
  }
  const first = load();
  assert.equal(first.control.value, '1440');
  first.control.value = '2880';
  vm.runInContext('saveMapPeriod()', first.context);
  assert.equal(load().control.value, '2880');
  storage.set('routiva.maps.period', 'invalid');
  assert.equal(load().control.value, '1440');
});

test('period remains usable when browser storage is blocked', () => {
  const control = {value:'', options:[{value:'1440'}]};
  const context = vm.createContext({$: () => control, localStorage: {
    getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); },
  }});
  vm.runInContext(source.slice(source.indexOf('const mapPeriodStorageKey'), source.indexOf('function changeMapQuery(')), context);
  vm.runInContext('restoreMapPeriod(); saveMapPeriod()', context);
  assert.equal(control.value, '1440');
});
