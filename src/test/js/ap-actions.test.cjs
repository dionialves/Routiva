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
    window: { open: (...args) => calls.push(['open', ...args]), openAccessPointConfiguration: id => calls.push(['configure', id]), location: { assign: url => calls.push(['navigate', url]) } },
    addEventListener() {}, MutationObserver: class { observe() {} }
  };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/host-menu.js', 'utf8'), context);
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/ap-actions.js', 'utf8'), context);
  return { nodes, calls, handlers };
}

test('configuration closes Actions before opening modal, even with API offline', () => {
  const ui = surface();
  ui.nodes['ap-settings-menu'].handlers.click({ target: { closest: () => ({ id: 'ap-configure' }) } });
  assert.deepEqual(ui.calls, ['close-menu', 'focus-trigger', ['configure', '42']]);
});

test('deleting current AP returns to list; saving or unrelated deletion does not navigate', () => {
  const ui = surface();
  const changed = ui.handlers['ap-configuration-changed'];
  changed({ detail: { hostId: '42', deleted: false } });
  changed({ detail: { hostId: '43', deleted: true } });
  assert.deepEqual(ui.calls, []);
  changed({ detail: { hostId: '42', deleted: true } });
  assert.deepEqual(ui.calls, [['navigate', '/ap']]);
});

test('browser action opens equipment IP in a separate tab even when API is offline', () => {
  const ui = surface();
  assert.equal(ui.nodes['ap-webfig'].disabled, false);
  ui.nodes['ap-webfig'].handlers.click();
  assert.deepEqual(ui.calls, [['open', 'http://192.0.2.1', '_blank', 'noopener,noreferrer']]);
});
