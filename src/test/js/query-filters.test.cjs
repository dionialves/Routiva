const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync('src/main/resources/static/query-filters.js', 'utf8');
function surface(pathname, controls = []) {
  const removed = [], navigated = [];
  const row = { children: [], matches: () => true, append(node) { this.children.push(node); },
    querySelectorAll: () => controls, dispatchEvent(event) { this.event = event.type; } };
  const node = () => ({ children: [], attributes: {}, listeners: {}, append(child) { this.children.push(child); },
    setAttribute(key, value) { this.attributes[key] = value; }, addEventListener(key, callback) { this.listeners[key] = callback; } });
  vm.runInNewContext(source, {
    document: { addEventListener: (_, callback) => callback(), querySelectorAll: () => [row],
      createElement: node, createElementNS: node },
    location: { pathname, assign: url => navigated.push(url) },
    localStorage: { removeItem: key => removed.push(key) }, Event: class { constructor(type) { this.type = type; } },
  });
  const button = row.children[0];
  button.listeners.click();
  return { row, button, removed, navigated };
}
test('clearing incident deep links removes saved filters and navigates without the event query', () => {
  const ui = surface('/incidentes');
  assert.deepEqual(ui.removed, ['routiva.incidents.filters']);
  assert.deepEqual(ui.navigated, ['/incidentes']);
  assert.equal(ui.button.attributes['aria-label'], 'Limpar todos os filtros');
  assert.equal(ui.button.children.length, 1);
});
test('period filters clear only their own persisted state and retain the current group route', () => {
  const ui = surface('/diagnostico/grupos/123');
  assert.deepEqual(ui.removed, ['routiva.diagnostics.period']);
  assert.deepEqual(ui.navigated, ['/diagnostico/grupos/123']);
});
test('AP filters restart with all hosts and groups without removing other preferences', () => {
  const ui = surface('/ap');
  assert.deepEqual(ui.removed, []);
  assert.deepEqual(ui.navigated, ['/ap']);
});
test('local searches and period controls reset before notifying their page', () => {
  const events = [];
  const search = { tagName: 'INPUT', value: 'router', dispatchEvent(event) { events.push([event.type, this.value]); } };
  const select = { tagName: 'SELECT', options: [{ defaultSelected: false }, { defaultSelected: true }],
    dispatchEvent(event) { events.push([event.type, this.options[1].selected]); } };
  const ui = surface('/ap/123', [search, select]);
  assert.equal(search.value, '');
  assert.equal(select.options[1].selected, true);
  assert.deepEqual(events, [['input', ''], ['change', true]]);
  assert.equal(ui.row.event, 'query-filters-cleared');
  assert.deepEqual(ui.navigated, []);
});
