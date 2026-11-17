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
test('host menu offers access actions and launches WinBox only on explicit action', () => {
  const { api, link, container } = surface();
  api.attach(link, container, { ip: '10.0.0.1', groupUrl: '/diagnostico/grupos/g' });
  const menu = container.children[0];
  const access = menu.children[0];
  assert.equal(access.children[1].href, 'http://10.0.0.1');
  assert.equal(access.children[2].href, 'winbox://10.0.0.1');
  assert.equal(access.children[3].href, 'ssh://10.0.0.1');
  let prevented = false;
  link.listeners.click({ preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(menu.open, true);
  assert.equal(access.children[1].focused, true);
  menu.listeners.toggle({ newState: 'open' });
  assert.equal(link.attributes['aria-expanded'], 'true');
  access.children[2].listeners.click();
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
test('AP actions remain hidden until inventory confirms the host', () => {
  const { api, link, container } = surface();
  // The resolver shares the same cached AP inventory used throughout the shell.
  let resolve;
  const context = { document: { createElement: () => new link.constructor() } };
  context.window = context;
  context.AccessPointLinks = { attach(_element, id, callback) {
    assert.equal(id, 'ap/1'); resolve = callback;
  } };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/host-menu.js', 'utf8'), context);
  context.HostMenu.attach(link, container, { ip: '10.0.0.1', hostId: 'ap/1' });
  const ap = container.children[0].children[1];
  assert.equal(ap.hidden, true);
  resolve('/ap/ap%2F1');
  assert.equal(ap.hidden, false);
  assert.equal(ap.children[2].href, '/ap/ap%2F1#avaliacao-latencia');
});
