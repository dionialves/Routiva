const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
class Element {
  constructor() { this.children = []; this.listeners = {}; this.dataset = {}; this.value = ''; this.attributes = {}; }
  append(...nodes) { this.children.push(...nodes); }
  replaceChildren() { this.children = []; }
  setAttribute(key, value) { this.attributes[key] = value; }
  addEventListener(name, fn) { this.listeners[name] = fn; }
  fire(name) { return this.listeners[name]?.({ preventDefault() {}, stopPropagation() {} }); }
}
async function setup(extraHosts = 0) {
  const ids = ['connection-form', 'selection-search', 'group-list', 'inventory-panel', 'inventory-hint', 'inventory-empty', 'selection-count', 'select-visible', 'clear-visible', 'last-sync'];
  const elements = Object.fromEntries(ids.map(id => [`#${id}`, new Element()]));
  elements['.settings-page'] = new Element(); elements['.settings-page'].dataset.synced = 'true';
  elements['#connection-form'].elements = { csrf: { value: 'token' } };
  const groupA = { id: 'a', name: 'Produção' }, groupB = { id: 'b', name: 'Filial' };
  const inventory = { groups: [groupA, groupB], hosts: [
    { id: '1', name: 'Compartilhado', ip: '10.0.0.1', groups: [groupA, groupB] },
    { id: '2', name: 'Servidor', ip: '10.0.0.2', groups: [groupA] }
  ], selectedHosts: [], selectedGroups: [] };
  for (let i = 0; i < extraHosts; i++) inventory.hosts.push({ id: `extra-${i}`, name: `Host ${i}`, ip: '', groups: [groupA] });
  const save = new Element(); save.dataset.action = 'selecao'; elements.save = save;
  elements.requests = [];
  let ready;
  const document = { querySelector: key => elements[key], querySelectorAll: () => [save], createElement: () => new Element(), addEventListener: (_, fn) => { ready = fn; } };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/zabbix-settings.js', 'utf8'), {
    document, window: { notify() {} }, fetch: async (url, options) => { elements.requests.push({ url, options }); return { ok: true, json: async () => options ? { message: 'Salvo' } : inventory }; }, FormData, URLSearchParams
  });
  ready(); await new Promise(resolve => setImmediate(resolve));
  return elements;
}
test('groups stay independent and removing a group preserves shared hosts in another selected group', async () => {
  const el = await setup(); const list = el['#group-list'];
  const group = i => list.children[i].children[0].children[0];
  const host = (i, j) => list.children[i].children[1].children[j].children[0];
  function toggle(i, checked) { const input = group(i); input.checked = checked; input.fire('change'); }
  toggle(0, true);
  assert.equal(group(1).checked, false);
  assert.equal(group(1).indeterminate, false);
  assert.equal(host(1, 0).checked, false);
  assert.equal(host(1, 0).disabled, true);
  assert.match(el['#selection-count'].textContent, /^2 hosts selecionados/);
  toggle(1, true);
  toggle(0, false);
  assert.equal(group(0).checked, false);
  assert.equal(group(1).checked, true);
  assert.equal(host(1, 0).checked, true);
  assert.match(el['#selection-count'].textContent, /^1 hosts selecionados/);
  toggle(0, true);
  toggle(1, false);
  assert.equal(group(0).checked, true);
  assert.equal(host(0, 0).checked, true);
  assert.equal(host(0, 1).checked, true);
  toggle(0, false);
  assert.match(el['#selection-count'].textContent, /^0 hosts selecionados/);
});
test('clearing a filtered group preserves hosts from another selected group', async () => {
  const el = await setup();
  el['#select-visible'].fire('click');
  el['#selection-search'].value = 'producao'; el['#selection-search'].fire('input');
  el['#clear-visible'].fire('click');
  el['#selection-search'].value = ''; el['#selection-search'].fire('input');
  const list = el['#group-list'];
  assert.equal(list.children[0].children[0].children[0].checked, false);
  assert.equal(list.children[1].children[0].children[0].checked, true);
  assert.equal(list.children[1].children[1].children[0].children[0].checked, true);
  assert.match(el['#selection-count'].textContent, /^1 hosts selecionados/);
});
test('search normalizes accents and shortcuts only change filtered hosts', async () => {
  const el = await setup(); const search = el['#selection-search'];
  search.value = 'producao'; search.fire('input'); assert.equal(el['#group-list'].children.length, 1);
  el['#select-visible'].fire('click');
  search.value = '10.0.0.1'; search.fire('input'); el['#clear-visible'].fire('click');
  assert.match(el['#selection-count'].textContent, /^1 hosts selecionados/);
  search.value = ''; search.fire('input');
  assert.equal(el['#group-list'].children[0].children[1].children[1].children[0].checked, true);
  const heading = el['#group-list'].children[0].children[0]; heading.fire('click');
  assert.equal(heading.children[1].attributes['aria-expanded'], 'true');
});

test('saving a large selection uses URL encoding and preserves every host and CSRF token', async () => {
  const el = await setup(200);
  el['#select-visible'].fire('click');
  el.save.fire('click');
  await new Promise(resolve => setImmediate(resolve));
  const request = el.requests.find(r => r.url.endsWith('/api/selecao'));
  assert.equal(request.options.method, 'POST');
  assert.ok(request.options.body instanceof URLSearchParams);
  const data = new URLSearchParams(request.options.body.toString());
  assert.equal(data.get('csrf'), 'token');
  assert.deepEqual(data.getAll('groups'), ['a', 'b']);
  assert.deepEqual(data.getAll('hosts'), ['1', '2', ...Array.from({ length: 200 }, (_, i) => `extra-${i}`)]);
});
