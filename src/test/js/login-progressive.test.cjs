const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function setup() {
  const nodes = new Map();
  const requests = [];
  const replies = [];
  const navigations = [];
  function get(id) {
    if (!nodes.has(id)) {
      const classes = new Set();
      nodes.set(id, {
        value: '', hidden: false, disabled: false, type: 'password', dataset: {}, listeners: {},
        classList: { toggle(name, on) { on ? classes.add(name) : classes.delete(name); } },
        setAttribute(name, value) { this[name] = value; },
        addEventListener(name, fn) { this.listeners[name] = fn; },
        focus() { this.focused = true; },
      });
    }
    return nodes.get(id);
  }
  get('login-form').dataset.setup = 'true';
  get('username').value = 'teste123';
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/login.js', 'utf8'), {
    document: { getElementById: get },
    FormData: class { constructor() { this.values = Object.fromEntries([...nodes].map(([id, n]) => [id, n.value])); } },
    fetch: async (path, options) => {
      requests.push({ path, options });
      const reply = replies.shift();
      return { ok: reply.ok, json: async () => reply.body };
    },
    window: { location: { assign: path => navigations.push(path) } },
  });
  const submit = () => get('login-form').listeners.submit({ preventDefault() {} });
  const input = (id, value) => { get(id).value = value; get(id).listeners.input(); };
  return { get, requests, replies, navigations, submit, input };
}

test('activation only reveals passwords after backend approval and submits the original code', async () => {
  const ui = setup();
  assert.equal(ui.get('password-fields').hidden, true);
  assert.equal(ui.get('validate-code').disabled, true);
  ui.input('code', 'original_code-123');
  ui.replies.push({ ok: false, body: { message: 'Código inválido.' } });
  await ui.submit();
  assert.equal(ui.get('password-fields').hidden, true);
  assert.equal(ui.get('code-error').textContent, 'Código inválido.');
  ui.replies.push({ ok: true, body: { validated: true } });
  const pending = ui.submit();
  await ui.submit();
  await pending;
  assert.equal(ui.requests.length, 2);
  assert.equal(ui.requests[1].path, '/login/validar-codigo');
  assert.equal(ui.get('code').readOnly, true);
  assert.equal(ui.get('password-fields').hidden, false);
  assert.equal(ui.get('login-submit').disabled, true);
  ui.input('password', 'SenhaForte#12345');
  ui.input('confirmation', 'diferente');
  assert.equal(ui.get('confirmation-error').hidden, false);
  ui.input('confirmation', 'SenhaForte#12345');
  assert.equal(ui.get('login-submit').disabled, false);
  ui.replies.push({ ok: true, body: { redirect: '/disponibilidade' } });
  await ui.submit();
  assert.equal(ui.requests[2].path, '/login/nova-senha');
  assert.equal(ui.requests[2].options.body.values.code, 'original_code-123');
  assert.deepEqual(ui.navigations, ['/disponibilidade']);
});

test('revoked code returns to validation and clear restores identification', async () => {
  const ui = setup();
  ui.input('code', 'old-code');
  ui.replies.push({ ok: true, body: { validated: true } });
  await ui.submit();
  ui.input('password', 'SenhaForte#12345');
  ui.input('confirmation', 'SenhaForte#12345');
  ui.replies.push({ ok: false, body: { codeInvalid: true, message: 'Código expirado.' } });
  await ui.submit();
  assert.equal(ui.get('code').readOnly, false);
  assert.equal(ui.get('password-fields').hidden, true);
  assert.equal(ui.get('password').value, '');
  ui.replies.push({ ok: true, body: { cleared: true } });
  ui.get('clear-user').listeners.click();
  assert.equal(ui.get('username').value, '');
  assert.equal(ui.get('code').value, '');
  assert.equal(ui.get('username').readOnly, false);
});
