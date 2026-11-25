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
  const context = { document: { createElement: () => new Element() }, innerWidth: 1000, innerHeight: 700, URL, location: {} };
  context.window = context;
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/host-menu.js', 'utf8'), context);
  return { context, api: context.HostMenu, link: new Element(), container: new Element() };
}
test('host menu offers access actions and launches WinBox only on explicit action', () => {
  const { api, link, container } = surface();
  api.attach(link, container, { ip: '10.0.0.1', groupUrl: '/diagnostico/grupos/g' });
  const menu = container.children[0];
  const access = menu.children[0];
  assert.equal(access.children[1].href, 'http://10.0.0.1');
  assert.equal(access.children[2].href, 'winbox://10.0.0.1');
  assert.equal(access.children[3].href, undefined);
  assert.equal(access.children[3].type, 'button');
  assert.equal(access.children[3].disabled, true);
  assert.equal(access.children[4].disabled, true);
  assert.equal(access.children[5].disabled, false);
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
  context.fetch = async () => ({ ok: true, json: async () => [] });
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

test('host menu does not offer equipment panels or query manufacturer lists', () => {
  const { link, container } = surface();
  const context = { document: { createElement: () => new link.constructor() },
    fetch: () => { throw new Error('Unexpected panel lookup'); } };
  context.window = context;
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/host-menu.js', 'utf8'), context);
  context.HostMenu.attach(link, container, { ip: '192.0.2.42', hostId: 'olt/42' });
  assert.equal(container.children[0].children.length, 2);
});

test('host section disables future features and links to host incidents and settings', () => {
  const { context, api, link, container } = surface();
  let opened;
  context.HostConfiguration = { open(id, trigger) { opened = { id, trigger }; } };
  api.attach(link, container, { hostId: 'host/42', ip: '192.0.2.42' });
  const host = container.children[0].children[1];
  assert.equal(host.children[0].textContent, 'HOST');
  assert.equal(host.children[1].disabled, true);
  assert.equal(host.children[2].disabled, true);
  assert.equal(host.children[3].href, '/incidentes?hostId=host%2F42');
  assert.equal(host.children[4].href, undefined);
  assert.equal(host.children[4].type, 'button');
  host.children[4].listeners.click();
  assert.equal(opened.id, 'host/42');
  assert.equal(opened.trigger, link);
});

test('SSH and Telnet launch separate local protocols using only the configured address and port', async () => {
  const { context, api, link, container } = surface();
  let requests = 0;
  context.fetch = async path => {
    assert.equal(path, '/api/hosts/42/terminal-access');
    requests++;
    return { ok: true, json: async () => ({ address: '2001:db8::42', sshPort: 2222, telnetPort: 2323, login: 'ignored' }) };
  };
  api.attach(link, container, { ip: '192.0.2.42', hostId: '42' });
  assert.equal(requests, 0);
  const [, , , ssh, telnet] = container.children[0].children[0].children;
  assert.equal(ssh.disabled, false);
  assert.equal(telnet.disabled, false);
  ssh.listeners.click();
  await new Promise(setImmediate);
  assert.equal(context.location.href, 'ssh://[2001:db8::42]:2222');
  telnet.listeners.click();
  await new Promise(setImmediate);
  assert.equal(context.location.href, 'telnet://[2001:db8::42]:2323');
  assert.equal(requests, 2);
});

test('terminal actions reject URL credentials, malformed hosts and invalid ports', async () => {
  const { context, api } = surface();
  const errors = [];
  context.notify = message => errors.push(message);
  for (const access of [
    { address: 'admin@router.example', sshPort: 22 },
    { address: 'a:b', sshPort: 22 },
    { address: 'router.example', sshPort: 65536 }
  ]) {
    context.fetch = async () => ({ ok: true, json: async () => access });
    await api.openTerminal('ssh', '42');
  }
  assert.equal(context.location.href, undefined);
  assert.equal(errors.length, 3);
});
