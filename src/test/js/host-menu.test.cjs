const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
function surface() {
  class Element {
    constructor() { this.children = []; this.listeners = {}; this.attributes = {}; this.style = {}; this.className = ''; }
    append(...items) { this.children.push(...items); }
    setAttribute(key, value) { this.attributes[key] = value; }
    addEventListener(key, fn) { this.listeners[key] = fn; }
    getBoundingClientRect() { return { left: 950, top: 650, bottom: 680 }; }
    showPopover() { this.open = true; }
    hidePopover() { this.open = false; }
    focus() { this.focused = true; }
  }
  const context = { document: { createElement: () => new Element() }, innerWidth: 1000, innerHeight: 700 };
  context.window = context;
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/host-menu.js', 'utf8'), context);
  return { api: context.HostMenu, link: new Element(), container: new Element() };
}
test('host menu preserves group navigation and launches WinBox only on explicit action', () => {
  const { api, link, container } = surface();
  api.attach(link, container, { ip: '10.0.0.1', groupUrl: '/diagnostico/grupos/g' });
  const menu = container.children[0];
  assert.equal(menu.children[0].href, '/diagnostico/grupos/g');
  assert.equal(menu.children[1].href, 'winbox://10.0.0.1');
  let prevented = false;
  link.listeners.click({ preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(menu.open, true);
  assert.equal(menu.children[0].focused, true);
  menu.listeners.toggle({ newState: 'open' });
  assert.equal(link.attributes['aria-expanded'], 'true');
  menu.children[1].listeners.click();
  assert.equal(menu.open, false);
});
test('missing and malformed addresses never produce a WinBox action', () => {
  const { api } = surface();
  for (const ip of ['', null, '10.0.0.1/path', 'user@host', '10.0.0.1?x', '<script>']) {
    assert.equal(api.winboxUrl(ip), null);
  }
  assert.equal(api.winboxUrl(' 10.0.0.1 '), 'winbox://10.0.0.1');
  assert.equal(api.winboxUrl('2001:db8::1'), 'winbox://[2001:db8::1]');
});
