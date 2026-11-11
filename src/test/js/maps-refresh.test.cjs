const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/maps.js'), 'utf8');

function setup() {
  const button = { disabled: false, setAttribute(key, value) { this[key] = value; } };
  const status = {};
  let resolve, reject;
  const pending = new Promise((yes, no) => { resolve = yes; reject = no; });
  const context = vm.createContext({
    $: id => id === 'refresh-map' ? button : status,
    current: { layout: { nodes: [{ hostId: '1' }] } }, editing: false,
    generation: 1, timer: 7, states: new Map(), summaryFailed: false,
    api: () => pending, draw() {}, popSummary: { render() {} },
    clearTimeout() {}, setTimeout: () => 8,
  });
  vm.runInContext(source.slice(source.indexOf('function setRefreshBusy('), source.indexOf('function openMap(')), context);
  return { button, status, context, resolve, reject };
}

test('manual refresh stays busy until incidents arrive and prevents duplicate clicks', async () => {
  const { button, context, resolve, status } = setup();
  const request = button.onclick();
  assert.equal(button.disabled, true);
  assert.equal(button['aria-busy'], 'true');
  button.onclick();
  assert.equal(context.generation, 2);
  resolve([{ id: '1', groups: [], incidents: [{ severity: 4 }] }]);
  await request;
  assert.equal(button.disabled, false);
  assert.equal(button['aria-busy'], 'false');
  assert.equal(context.states.get('host:1')[0].incidents[0].severity, 4);
  assert.match(status.textContent, /Incidentes atualizados/);
});

test('failed refresh stops the spinner and preserves existing incident data', async () => {
  const { button, context, reject, status } = setup();
  context.states.set('host:1', ['cached']);
  const request = button.onclick();
  reject(new Error('offline'));
  await request;
  assert.equal(button.disabled, false);
  assert.equal(button['aria-busy'], 'false');
  assert.deepEqual(context.states.get('host:1'), ['cached']);
  assert.match(status.textContent, /indisponível/);
});
