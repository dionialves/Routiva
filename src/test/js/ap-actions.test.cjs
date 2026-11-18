const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function surface() {
  const calls = [], handlers = {};
  const root = { dataset: { hostId: '42', managementIp: '192.0.2.1', api: 'unavailable' } };
  class Element {
    constructor() { this.handlers = {}; }
    addEventListener(type, fn) { this.handlers[type] = fn; }
    querySelectorAll() { return []; }
    matches() { return true; }
    hidePopover() { calls.push('close-menu'); }
    focus() { calls.push('focus-trigger'); }
    setAttribute() {}
  }
  const nodes = new Proxy({}, { get: (o, key) => o[key] ||= new Element() });
  const context = {
    document: { querySelector: () => root, getElementById: id => nodes[id], addEventListener(type, fn) { handlers[type] = fn; } },
    window: { open: (...args) => calls.push(['open', ...args]), location: { assign: url => calls.push(['navigate', url]) } },
    addEventListener() {}, MutationObserver: class { observe() {} }
  };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/host-menu.js', 'utf8'), context);
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/ap-actions.js', 'utf8'), context);
  return { nodes, calls, handlers };
}

test('browser action opens equipment IP in a separate tab even when API is offline', () => {
  const ui = surface();
  assert.equal(ui.nodes['ap-webfig'].disabled, false);
  ui.nodes['ap-webfig'].handlers.click();
  assert.deepEqual(ui.calls, [['open', 'http://192.0.2.1', '_blank', 'noopener,noreferrer']]);
});
