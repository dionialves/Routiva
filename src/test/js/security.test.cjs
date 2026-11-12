const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/security.js', 'utf8');
function setup(status = 200) {
  const requests = [], navigations = [], listeners = {};
  const window = {
    location: { href: 'https://example.test/mapas', origin: 'https://example.test', assign: path => navigations.push(path) },
    fetch: async (input, options) => { requests.push({ input, options }); return { status }; }
  };
  const document = {
    querySelector: selector => ({ content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'session-token' }),
    addEventListener: (name, callback) => { listeners[name] = callback; }
  };
  vm.runInNewContext(source, { window, document, URL, Request, Headers });
  return { window, requests, navigations, listeners };
}
test('unsafe same-origin requests include CSRF without losing existing headers', async () => {
  const { window, requests } = setup();
  await window.fetch('/api/maps', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
  assert.equal(requests[0].options.headers.get('X-CSRF-TOKEN'), 'session-token');
  assert.equal(requests[0].options.headers.get('Content-Type'), 'application/json');
  assert.equal(requests[0].options.body, '{}');
  await window.fetch(new Request('https://example.test/api/users/1', { method: 'PUT', headers: { 'X-Test': 'preserved' } }));
  assert.equal(requests[1].options.headers.get('X-CSRF-TOKEN'), 'session-token');
  assert.equal(requests[1].options.headers.get('X-Test'), 'preserved');
});
test('read requests and foreign origins do not receive the session CSRF token', async () => {
  const { window, requests } = setup();
  await window.fetch('/api/maps');
  await window.fetch('https://remote.test/api', { method: 'POST' });
  assert.equal(requests[0].options.headers, undefined);
  assert.equal(requests[1].options.headers, undefined);
});
test('expired application sessions redirect to login', async () => {
  const { window, navigations } = setup(401);
  await window.fetch('/api/maps');
  assert.deepEqual(navigations, ['/login']);
});
