const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const flush = () => new Promise(resolve => setImmediate(resolve));

function surface(config = {}) {
  class Element {
    constructor() { this.value = ''; this.handlers = {}; this.hidden = true; }
    addEventListener(type, handler) { this.handlers[type] = handler; }
    setAttribute(name, value) { this[name] = value; }
    reset() {}
    reportValidity() { return this.valid !== false; }
    showModal() { this.open = true; }
    close() { this.open = false; this.handlers.close?.(); }
    focus() { this.focused = true; }
  }
  const nodes = new Proxy({}, { get: (object, key) => object[key] ||= new Element() });
  const requests = [], events = [], notifications = [], notificationTypes = [];
  let failure = false, savedStatus = 'ACTIVE', testSuccess = true, held;
  const context = {
    window: { notify: (message, type = 'success') => { notifications.push(message); notificationTypes.push(type); } }, CustomEvent: class { constructor(type) { this.type = type; } },
    document: { getElementById: id => nodes[id], dispatchEvent: event => events.push(event.type) },
    fetch: async (url, options = {}) => {
      requests.push({ url, ...options });
      if (held) await held;
      return { ok: !failure, json: async () => failure ? { message: 'Operação indisponível' }
        : options.method === 'PUT' ? { status: savedStatus, message: savedStatus === 'ACTIVE' ? 'Conexão validada.' : 'Conexão recusada.' }
        : options.method === 'POST' ? { success: testSuccess, message: testSuccess ? 'Conexão validada.' : 'Conexão recusada.' }
        : options.method === 'DELETE' ? { deleted: true }
        : { name: '<AP>', ip: '192.0.2.1', manufacturer: 'MikroTik', login: 'viewer', port: 8729, enabled: true, tls: false, passwordConfigured: true, ...config } };
    }
  };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/access-point-config.js', 'utf8'), context);
  const node = name => nodes['ap-config-' + name];
  return { node, requests, events, notifications, notificationTypes, testResult: value => { testSuccess = value; }, open: id => context.window.openAccessPointConfiguration(id),
    click: async name => { await node(name).handlers.click(); await flush(); },
    save: async () => { node('form').handlers.submit({ preventDefault() {} }); await flush(); },
    fail: () => { failure = true; }, status: value => { savedStatus = value; },
    hold: () => { let release; held = new Promise(resolve => { release = resolve; }); return release; } };
}

test('loads inherited values and never fills the password', async () => {
  const ui = surface(); await ui.open('42');
  assert.equal(ui.node('name').value, '<AP>');
  assert.equal(ui.node('ip').value, '192.0.2.1');
  assert.equal(ui.node('password').value, '');
  assert.equal(ui.node('password').required, false);
  assert.equal(ui.node('enabled').value, 'true');
  assert.equal(ui.node('dialog').open, true);
});

test('new AP requires a password and defaults to inactive and native API port', async () => {
  const ui = surface({ manufacturer: null, login: null, port: null, enabled: false, passwordConfigured: false });
  await ui.open('42');
  assert.equal(ui.node('password').required, true);
  assert.equal(ui.node('enabled').value, 'false');
  assert.equal(ui.node('port').value, 8728);
  ui.node('form').valid = false; await ui.save();
  assert.equal(ui.requests.length, 1);
});

test('test connection submits current values without saving or closing', async () => {
  const ui = surface(); await ui.open('42');
  ui.node('password').value = 'individual-secret';
  ui.node('port').value = '1234';
  await ui.click('test');
  const request = ui.requests.at(-1);
  assert.equal(request.url, '/api/ap/42/configuration/test');
  assert.equal(request.method, 'POST');
  assert.deepEqual(JSON.parse(request.body), { manufacturer: 'MikroTik', login: 'viewer', password: 'individual-secret', port: 1234, enabled: true, tls: false });
  assert.deepEqual(ui.events, []);
  assert.equal(ui.node('dialog').open, true);
});

test('saving success keeps dialog open, clears password and uses system notification', async () => {
  const ui = surface(); await ui.open('42'); ui.node('password').value = 'secret';
  await ui.save();
  assert.equal(ui.requests.at(-1).method, 'PUT');
  assert.equal(ui.node('password').value, '');
  assert.equal(ui.node('dialog').open, true);
  assert.deepEqual(ui.notifications, ['Configuração salva e conexão validada com sucesso.']);
  assert.deepEqual(ui.events, ['ap-configuration-changed']);
});

test('failed connection still saves and refreshes, leaving the reason visible', async () => {
  const ui = surface(); ui.status('CONNECTION_FAILED'); await ui.open('42');
  ui.node('password').value = 'wrong'; await ui.save();
  assert.equal(ui.node('dialog').open, true);
  assert.equal(ui.node('state').textContent, '');
  assert.deepEqual(ui.notifications, ['Conexão recusada.']);
  assert.equal(ui.node('password').value, '');
  assert.equal(ui.node('password').required, false);
  assert.deepEqual(ui.events, ['ap-configuration-changed']);
});

test('deletion requires confirmation and only calls the local AP endpoint', async () => {
  const ui = surface(); await ui.open('42'); await ui.click('delete');
  assert.equal(ui.node('delete-confirm').hidden, false);
  assert.equal(ui.requests.length, 1);
  await ui.click('delete-cancel');
  assert.equal(ui.node('delete-confirm').hidden, true);
  await ui.click('delete'); await ui.click('delete-confirm-button');
  assert.equal(ui.requests.at(-1).url, '/api/ap/42');
  assert.equal(ui.requests.at(-1).method, 'DELETE');
  assert.deepEqual(ui.events, ['ap-configuration-changed']);
  assert.equal(ui.node('dialog').open, false);
});

test('request errors keep dialog open and reenable controls for retry', async () => {
  const ui = surface(); await ui.open('42'); ui.fail(); await ui.save();
  assert.equal(ui.node('dialog').open, true);
  assert.equal(ui.node('save').disabled, false);
  assert.equal(ui.node('state').textContent, '');
  assert.deepEqual(ui.notifications, ['Operação indisponível']);
  assert.deepEqual(ui.events, []);
});

test('pending requests prevent duplicate actions and Escape closes after completion', async () => {
  const ui = surface(); await ui.open('42'); const release = ui.hold();
  ui.node('form').handlers.submit({ preventDefault() {} });
  assert.equal(ui.node('save').disabled, true);
  assert.equal(ui.node('fields').disabled, true);
  let prevented = false;
  ui.node('dialog').handlers.cancel({ preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  await ui.save(); assert.equal(ui.requests.length, 2);
  release(); await flush();
  prevented = false;
  ui.node('dialog').handlers.cancel({ preventDefault() { prevented = true; } });
  assert.equal(prevented, false);
});

test('TLS belongs to the AP and preserves custom ports', async () => {
  const ui = surface({ tls: true, port: 1234 }); await ui.open('42');
  assert.equal(ui.node('tls').value, 'true');
  ui.node('tls').value = 'false'; ui.node('tls').handlers.change();
  assert.equal(ui.node('port').value, 1234);
  ui.node('port').value = '8728'; ui.node('tls').value = 'true'; ui.node('tls').handlers.change();
  assert.equal(ui.node('port').value, 8729);
  await ui.click('test');
  assert.equal(JSON.parse(ui.requests.at(-1).body).tls, true);
});

test('connection test success uses only the standard success notification', async () => {
  const ui = surface(); await ui.open('42'); await ui.click('test');
  assert.deepEqual(ui.notifications, ['Conexão validada com sucesso']);
  assert.deepEqual(ui.notificationTypes, ['success']);
  assert.equal(ui.node('state').textContent, '');
  assert.equal(ui.node('dialog').open, true);
});

test('connection test rejection uses only the standard error notification', async () => {
  const ui = surface(); await ui.open('42'); ui.testResult(false); await ui.click('test');
  assert.deepEqual(ui.notifications, ['Conexão recusada.']);
  assert.deepEqual(ui.notificationTypes, ['error']);
  assert.equal(ui.node('state').textContent, '');
  assert.equal(ui.node('dialog').open, true);
});

test('connection test request failure also uses the standard error notification', async () => {
  const ui = surface(); await ui.open('42'); ui.fail(); await ui.click('test');
  assert.deepEqual(ui.notifications, ['Operação indisponível']);
  assert.deepEqual(ui.notificationTypes, ['error']);
  assert.equal(ui.node('state').textContent, '');
  assert.equal(ui.node('test').disabled, false);
});

test('Ubiquiti uses only its SSH port and never submits TLS', async () => {
  const ui = surface({ manufacturer: 'Ubiquiti', port: 2222, tls: true });
  await ui.open('42');
  assert.equal(ui.node('api-row').hidden, true);
  assert.equal(ui.node('tls-row').hidden, true);
  assert.equal(ui.node('port').disabled, true);
  assert.equal(ui.node('port').required, false);
  assert.equal(ui.node('ssh-row').hidden, false);
  assert.equal(ui.node('ssh-port').value, 2222);
  assert.equal(ui.node('ssh-port').disabled, false);
  assert.equal(ui.node('ssh-port').required, true);
  await ui.click('test');
  const body = JSON.parse(ui.requests.at(-1).body);
  assert.equal(body.port, 2222);
  assert.equal(body.tls, false);
});

test('switching manufacturers preserves custom ports and restores API controls', async () => {
  const ui = surface({ port: 1234, tls: true }); await ui.open('42');
  ui.node('manufacturer').value = 'Ubiquiti'; ui.node('manufacturer').handlers.change();
  assert.equal(ui.node('ssh-port').value, 22);
  ui.node('ssh-port').value = '2222';
  ui.node('manufacturer').value = 'MikroTik'; ui.node('manufacturer').handlers.change();
  assert.equal(ui.node('api-row').hidden, false);
  assert.equal(ui.node('tls-row').hidden, false);
  assert.equal(ui.node('ssh-row').hidden, true);
  assert.equal(ui.node('ssh-port').disabled, true);
  assert.equal(ui.node('ssh-port').required, false);
  assert.equal(ui.node('port').value, 1234);
  await ui.click('test');
  assert.equal(JSON.parse(ui.requests.at(-1).body).tls, true);
});
