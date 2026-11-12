const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const flush = () => new Promise(resolve => setImmediate(resolve));
async function surface(initialRows = []) {
  class Element {
    constructor() { this.children = []; this.value = ''; this.handlers = {}; this.disabled = false; }
    addEventListener(type, handler) { this.handlers[type] = handler; }
    setAttribute(name, value) { this[name] = value; }
    prepend(...children) { this.children.unshift(...children); }
    get parentElement() { return this.parent ||= new Element(); }
    get lastElementChild() { return this.last ||= new Element(); }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
    get selectedOptions() { return this.children.filter(child => child.selected); }
    showModal() { this.open = true; }
    close() { this.open = false; }
  }
  const elements = new Proxy({}, { get: (object, key) => object[key] ||= new Element() });
  const requests = [];
  let fail = false;
  const rows = [{ hostId: '42', name: 'AP A', ip: '192.0.2.42', available: true, connectedClients: 0 }];
  const context = {
    window: { setupMultiPicker(kind, select, label, onChange) { select.addEventListener('change', onChange); return () => {}; } },
    document: { querySelectorAll: () => [], addEventListener() {}, getElementById: id => elements[id], createElement: () => new Element(), createDocumentFragment: () => new Element() },
    fetch: async (url, options) => {
      requests.push({ url, options });
      if (options.method === 'POST') return { ok: !fail, json: async () => fail ? { message: 'Inventário alterado' } : rows };
      return { ok: true, json: async () => url.endsWith('/candidates') ? [{ id: '42', name: '<AP A>', ip: '192.0.2.42' }, { id: '43', name: 'AP B', ip: '192.0.2.43' }] : initialRows };
    }
  };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/access-points.js', 'utf8'), context);
  await flush();
  return { elements, requests, fail: () => { fail = true; }, click: async id => { await elements['ap-' + id].handlers.click(); await flush(); } };
}
function mark(e, side, index = 0) {
  const checkbox = e['ap-' + side].children[index].children[0].children[0];
  checkbox.checked = true; checkbox.handlers.change();
}
const hostRows = (e, side) => e['ap-' + side].children.filter(row => row.className === 'ap-host-row');

test('arrows physically transfer hosts and only the saved selection is posted', async () => {
  const ui = await surface(), e = ui.elements;
  await ui.click('import');
  assert.equal(e['ap-available'].children.length, 2);
  assert.equal(e['ap-save'].disabled, true);
  mark(e, 'available');
  await ui.click('select');
  assert.equal(e['ap-available'].children.length, 1);
  assert.equal(e['ap-selected'].children[0].children[0].children[1].textContent, '<AP A> · 192.0.2.42');
  mark(e, 'selected');
  await ui.click('remove');
  assert.equal(hostRows(e, 'selected').length, 0);
  assert.equal(e['ap-available'].children.length, 2);
  mark(e, 'available');
  await ui.click('select'); await ui.click('save');
  const posted = ui.requests.find(request => request.options.method === 'POST');
  assert.deepEqual(JSON.parse(posted.options.body), { hostIds: ['42'] });
  assert.equal(e['ap-dialog'].open, false);
  assert.equal(e['ap-rows'].children[0].children[0].children[0].children[0].href, '/ap/42');
  assert.equal(e['ap-rows'].children[0].children[0].children[4].textContent, 0);
});
test('failed save preserves selection and cancel does not persist', async () => {
  const ui = await surface(), e = ui.elements;
  await ui.click('import'); mark(e, 'available');
  await ui.click('select'); ui.fail(); await ui.click('save');
  assert.equal(e['ap-dialog'].open, true);
  assert.equal(e['ap-selected'].children.length, 1);
  assert.equal(e['ap-import-state'].textContent, 'Inventário alterado');
  const count = ui.requests.length; await ui.click('cancel');
  assert.equal(ui.requests.length, count);
});

test('host selection, numeric sorting and pagination compose without new requests', async () => {
  const rows = Array.from({ length: 55 }, (_, index) => ({ hostId: String(index), name: `AP ${index + 1}`, ip: `192.0.2.${index + 1}`, available: true, groups: [{ id: index < 52 ? 'a' : 'b', name: index < 52 ? 'Grupo A' : 'Grupo B' }] }));
  const ui = await surface(rows), e = ui.elements;
  assert.equal(e['ap-page-count'].textContent, 'Página 1 de 2');
  assert.equal(e['ap-rows'].children[0].children.length, 50);
  await ui.click('next');
  assert.equal(e['ap-page-count'].textContent, 'Página 2 de 2');
  for (const option of e['incident-host'].children.slice(0, 52)) option.selected = true;
  e['incident-host'].handlers.change();
  assert.equal(e['ap-page-count'].textContent, 'Página 1 de 2');
  await ui.click('sort-ip'); await ui.click('sort-ip');
  const firstRow = () => e['ap-rows'].children[0].children[0];
  assert.equal(firstRow().children[1].textContent, '192.0.2.52');
  await ui.click('next');
  assert.equal(firstRow().children[1].textContent, '192.0.2.2');
  for (const option of e['incident-host'].children) option.selected = option.value === '21';
  e['incident-host'].handlers.change();
  assert.equal(e['ap-page-count'].textContent, 'Página 1 de 1');
  assert.equal(e['ap-next'].disabled, true);
  assert.equal(ui.requests.length, 1);
});


test('search preserves marks, counts include hidden hosts, and individual removal stays local', async () => {
  const ui = await surface(), e = ui.elements;
  await ui.click('import');
  mark(e, 'available');
  e['ap-available-search'].value = 'AP B';
  e['ap-available-search'].handlers.input();
  assert.equal(e['ap-available-count'].textContent, '(2)');
  assert.equal(hostRows(e, 'available').length, 1);
  mark(e, 'available');
  await ui.click('select');
  assert.equal(e['ap-selected-count'].textContent, '(2)');
  assert.equal(e['ap-select'].disabled, true);
  e['ap-selected-search'].value = '192.0.2.42';
  e['ap-selected-search'].handlers.input();
  assert.equal(hostRows(e, 'selected').length, 1);
  const count = ui.requests.length;
  e['ap-selected'].children[0].children[1].handlers.click();
  assert.equal(e['ap-selected-count'].textContent, '(1)');
  assert.equal(e['ap-available-count'].textContent, '(1)');
  assert.equal(ui.requests.length, count);
  assert.equal(e['ap-selected'].children[0].textContent, 'Nenhum host encontrado.');
});

test('empty selection cannot submit', async () => {
  const ui = await surface();
  await ui.click('import'); await ui.click('save');
  assert.equal(ui.requests.filter(request => request.options.method === 'POST').length, 0);
});

test('host groups filter APs before combining host selection and reset pagination', async () => {
  const rows = Array.from({ length:55 }, (_, i) => ({
    hostId:String(i), name:`AP ${i}`, ip:`192.0.2.${i}`, available:true,
    groups:i === 54 ? [] : [{ id:i < 52 ? 'a' : 'b', name:i < 52 ? 'Água' : 'Centro' }],
  }));
  const ui = await surface(rows), e = ui.elements;
  assert.equal(e['incident-group'].children.length, 2);
  await ui.click('next');
  e['incident-group'].children.find(option => option.value === 'b').selected = true;
  e['incident-group'].handlers.change();
  assert.equal(e['ap-page-count'].textContent, 'Página 1 de 1');
  assert.equal(e['ap-rows'].children[0].children.length, 2);
  e['incident-host'].children.find(option => option.value === '52').selected = true;
  e['incident-host'].handlers.change();
  assert.equal(e['ap-rows'].children[0].children.length, 1);
  e['incident-host'].children.find(option => option.value === '0').selected = true;
  e['incident-host'].handlers.change();
  assert.equal(e['ap-rows'].children[0].children.length, 1);
  e['incident-group'].children.forEach(option => { option.selected = false; });
  e['incident-group'].handlers.change();
  assert.equal(e['ap-rows'].children[0].children.length, 2);
  assert.equal(ui.requests.length, 1);
});
