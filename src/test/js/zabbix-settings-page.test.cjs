const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
class Element {
  constructor() { this.children = []; this.listeners = {}; this.dataset = {}; this.value = ''; this.attributes = {}; this.isConnected = true; }
  append(...nodes) { this.children.push(...nodes); }
  replaceChildren() { this.children = []; }
  setAttribute(key, value) { this.attributes[key] = value; }
  getAttribute(key) { return this.attributes[key]; }
  addEventListener(name, fn) { this.listeners[name] = fn; }
  fire(name) { return this.listeners[name]?.({ preventDefault() {} }); }
  querySelectorAll() { return []; }
}
async function setup(size = 2) {
  const ids = ['connection-status-text', 'groups-total', 'hosts-total', 'hosts-selected', 'selection-changes', 'save-selection-text', 'import-search', 'import-severity', 'import-sort', 'import-sort-heading', 'import-previous', 'import-next', 'import-details', 'import-execution', 'import-summary', 'import-filters', 'import-log', 'import-page', 'import-detail-fields', 'connection-form', 'connection-status', 'connection-feedback', 'inventory-feedback', 'inventory-panel', 'inventory-summary', 'refresh-inventory', 'test-connection', 'group-search', 'group-list', 'group-title', 'group-all', 'host-search', 'selection-filter', 'host-list', 'page-label', 'previous-page', 'next-page', 'save-selection', 'cancel-selection', 'selection-count', 'import-result', 'last-query'];
  const elements = Object.fromEntries(ids.map(id => [id, new Element()]));
  elements['connection-form'].elements = { csrf: { value: 'token' } };
  elements['selection-filter'].value = 'all';
  const group = { id: 'a', name: 'Produção' }, other = { id: 'b', name: 'Filial' };
  const data = { groups: [group, other], hosts: Array.from({ length: size }, (_, i) => ({ id: String(i), name: `Host ${i}`, ip: `10.0.0.${i + 1}`, groups: [group, other] })), selectedHosts: [], imported: [], queriedAt: 123 };
  let ready, fail = false;
  const requests = [];
  const document = { activeElement: null, getElementById: id => elements[id], createElement: () => new Element(),
    querySelector: () => ({ dataset: { configured: 'true' } }), querySelectorAll: () => [], addEventListener: (_, fn) => { ready = fn; } };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/zabbix-import-results.js', 'utf8') + '\n' + fs.readFileSync('src/main/resources/static/zabbix-settings.js', 'utf8'), {
    document, window: { notify() {}, addEventListener() {}, AppIcon: () => new Element() }, URLSearchParams, FormData,
    fetch: async (url, options) => {
      requests.push({ url, options });
      if (!options) return { ok: !fail, json: async () => data };
      return { ok: true, json: async () => ({ selectedHosts: JSON.parse(options.body).hosts, imported: data.hosts, result: { state: 'completed', imported: size, updated: 0, unchanged: 0, ignored: 0, failed: 0 } }) };
    }
  });
  ready(); await new Promise(resolve => setImmediate(resolve));
  return { elements, requests, data, failRefresh: () => { fail = true; } };
}
test('page paginates hosts, group navigation does not change selection, filters retain it', async () => {
  const { elements: el } = await setup(120);
  assert.equal(el['host-list'].children.length, 50);
  el['group-list'].children[1].children[1].fire('click');
  assert.match(el['selection-count'].textContent, /^0 hosts selecionados/);
  const check = el['host-list'].children[0].children[0].children[0]; check.checked = true; check.fire('change');
  assert.equal(el['group-list'].children[0].children[0].indeterminate, true);
  assert.equal(el['group-list'].children[1].children[0].indeterminate, true);
  el['selection-filter'].value = 'selected'; el['selection-filter'].fire('change');
  assert.equal(el['host-list'].children.length, 1);
  el['selection-filter'].value = 'all'; el['selection-filter'].fire('change');
  el['next-page'].fire('click'); assert.match(el['page-label'].textContent, /Página 2 de 3/);
  assert.match(el['selection-count'].textContent, /^1 hosts selecionados/);
});
test('refresh never imports, preserves edits on success and failure, cancel restores baseline', async () => {
  const { elements: el, requests, failRefresh } = await setup();
  el['group-all'].checked = true; el['group-all'].fire('change');
  await el['refresh-inventory'].fire('click');
  assert.match(el['selection-count'].textContent, /^2 hosts selecionados/);
  assert.equal(requests.filter(r => r.options).length, 0);
  failRefresh(); await el['refresh-inventory'].fire('click');
  assert.equal(el['connection-status'].dataset.state, 'error');
  assert.match(el['selection-count'].textContent, /^2 hosts selecionados/);
  el['cancel-selection'].fire('click'); assert.match(el['selection-count'].textContent, /^0 hosts selecionados/);
});
test('save sends JSON and disables duplicate submissions until backend confirms import', async () => {
  const { elements: el, requests } = await setup(1100);
  el['group-all'].checked = true; el['group-all'].fire('change');
  const saving = el['save-selection'].fire('click');
  assert.equal(el['save-selection'].disabled, true);
  await el['save-selection'].fire('click'); await saving;
  const mutations = requests.filter(r => r.options);
  assert.equal(mutations.length, 1);
  assert.equal(mutations[0].url, '/configuracao/zabbix/api/importar');
  assert.equal(mutations[0].options.headers['X-Zabbix-CSRF'], 'token');
  assert.equal(JSON.parse(mutations[0].options.body).hosts.length, 1100);
  assert.match(el['selection-changes'].textContent, /0 seleções alteradas/);
  assert.equal(el['import-summary'].children[1].children[1].children[1].textContent, '1100');
});
