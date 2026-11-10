const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/maps.js'), 'utf8');
const context = vm.createContext({ current: { layout: { minimumSeverity: 2 } } });
vm.runInContext(source.slice(source.indexOf('function hostLevel('), source.indexOf('function checkpoint(')), context);
const host = incidents => ({ incidents: incidents.map(severity => ({ severity })) });

test('minimum severity ignores lower incidents and includes the selected level', () => {
  context.host = host([0, 1]);
  assert.equal(vm.runInContext('hostLevel(host)', context), -1);
  context.host = host([0, 1, 2]);
  assert.equal(vm.runInContext('hostLevel(host)', context), 2);
  context.host = host([1, 5]);
  assert.equal(vm.runInContext('hostLevel(host)', context), 5);
});

test('groups apply the same filter and retain missing-data states', () => {
  context.hosts = [host([0]), host([1])];
  assert.equal(vm.runInContext('groupLevel(hosts)', context), -1);
  context.hosts.push(host([4]));
  assert.equal(vm.runInContext('groupLevel(hosts)', context), 4);
  context.hosts = [host([1]), {}];
  assert.equal(vm.runInContext('groupLevel(hosts)', context), -2);
  assert.equal(vm.runInContext('groupLevel([])', context), -2);
});

test('legacy maps include all severities by default', () => {
  context.current = { layout: {} };
  context.host = host([0]);
  assert.equal(vm.runInContext('hostLevel(host)', context), 0);
});

test('POP colors respect map threshold, including information and unknown monitoring', () => {
  context.current = {layout: {minimumSeverity: 1}};
  context.hosts = [host([0])];
  assert.equal(vm.runInContext('groupLevel(hosts)', context), -1);
  context.hosts.push(host([1]));
  assert.equal(vm.runInContext('groupLevel(hosts)', context), 1);
  context.current.layout.minimumSeverity = 5;
  assert.equal(vm.runInContext('groupLevel(hosts)', context), -1);
  context.hosts = [{incidents: [{severity: 0}], monitoring: 'unknown'}];
  assert.equal(vm.runInContext('groupLevel(hosts)', context), -2);
});
