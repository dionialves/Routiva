const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function setup({ needsSetup = true, identified = false } = {}) {
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
        toggleAttribute(name, on) { this[name] = on; },
        addEventListener(name, fn) { this.listeners[name] = fn; },
        focus() { this.focused = true; },
      });
    }
    return nodes.get(id);
  }
  get('login-form').dataset.setup = String(needsSetup);
  get('login-form').dataset.identified = String(identified);
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
  assert.equal(ui.get('setup-submit').disabled, true);
  ui.input('password', 'SenhaForte#12345');
  ui.input('confirmation', 'diferente');
  assert.equal(ui.get('confirmation-error').hidden, false);
  ui.input('confirmation', 'SenhaForte#12345');
  assert.equal(ui.get('setup-submit').disabled, false);
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

test('normal login makes only the current action the implicit submit button', async () => {
  const ui = setup({ needsSetup: false });
  const continueButton = ui.get('identify-user');
  const enterButton = ui.get('login-submit');
  ui.input('username', ' ');
  assert.equal(continueButton.disabled, true);
  assert.equal(continueButton.type, 'submit');
  assert.equal(enterButton.type, 'button');

  ui.input('username', 'teste123');
  ui.replies.push({ ok: true, body: { username: 'teste123', setup: false } });
  const identifying = ui.submit();
  assert.equal(continueButton['aria-busy'], 'true');
  await identifying;
  assert.equal(continueButton.type, 'button');
  assert.equal(enterButton.type, 'submit');
  assert.equal(enterButton.disabled, true);
  assert.equal(ui.get('clear-user').hidden, false);
  assert.equal(ui.get('password').focused, true);

  ui.input('password', 'senha');
  assert.equal(enterButton.disabled, false);
  ui.get('toggle-password').listeners.click();
  assert.equal(ui.get('password').type, 'text');
  assert.equal(ui.get('eye-slash').hidden, false);
  ui.get('toggle-password').listeners.click();
  assert.equal(ui.get('password').type, 'password');
  assert.equal(ui.get('eye-slash').hidden, true);
  ui.replies.push({ ok: false, body: { message: 'Senha inválida.' } });
  const authenticating = ui.submit();
  assert.equal(enterButton['aria-busy'], 'true');
  assert.equal(enterButton.disabled, true);
  await ui.submit();
  await authenticating;
  assert.equal(ui.requests.length, 2);
  assert.equal(ui.requests[1].path, '/login/senha');
  assert.equal(ui.get('login-error').textContent, 'Senha inválida.');
  assert.equal(enterButton['aria-busy'], 'false');
  assert.equal(enterButton.disabled, true);
});

test('direct password page starts at the password stage', () => {
  const ui = setup({ needsSetup: false, identified: true });
  assert.equal(ui.get('password-stage').hidden, false);
  assert.equal(ui.get('username').readOnly, true);
  assert.equal(ui.get('login-submit').type, 'submit');
});
