const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../../main/resources/static/maps.js'), 'utf8');
test('submap navigation retains ancestry, maximizes and handles unavailable targets', () => {
  const child = {id:'child'};
  const calls = [];
  const context = vm.createContext({editing:false, tool:'select', current:{id:'parent'},
    maps:[child], mapParents:['root'], openMap:(...args)=>calls.push(args),
    setMapExpanded:value=>calls.push(value), fitToView:()=>calls.push('fit'), window:{notify:message=>calls.push(message)}});
  vm.runInContext(source.slice(source.indexOf('function choose('), source.indexOf('const viewport =')), context);
  vm.runInContext("choose({groupId:'group',subMapId:'child'})", context);
  assert.equal(calls[0][0], child);
  assert.deepEqual(Array.from(calls[0][2]), ['root','parent']);
  assert.equal(calls[1], true);
  assert.equal(calls[2], 'fit');
  vm.runInContext("choose({groupId:'group',subMapId:'removed'})", context);
  assert.equal(calls[3], 'Submapa indisponível.');
});
test('return link opens the parent in the same view, maximized', () => {
  const button = {};
  const parent = {id:'parent'};
  const calls = [];
  const context = vm.createContext({$:()=>button, maps:[parent],
    openMap:(...args)=>calls.push(args), setMapExpanded:value=>calls.push(value), fitToView:()=>calls.push('fit'),
    window:{notify:message=>calls.push(message)}});
  vm.runInContext(source.slice(source.indexOf('let mapParents = [];'), source.indexOf('const pageSize =')), context);
  vm.runInContext("mapParents = ['root', 'parent']", context);
  button.onclick({preventDefault(){}});
  assert.equal(calls[0][0], parent);
  assert.deepEqual(Array.from(calls[0][2]), ['root']);
  assert.equal(calls[1], true);
  assert.equal(calls[2], 'fit');
});
