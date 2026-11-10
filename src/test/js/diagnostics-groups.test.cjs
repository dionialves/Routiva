const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

async function surface(groups) {
  class Element {
    constructor() { this.children = []; this.listeners = {}; this.value = ''; }
    append(...items) { this.children.push(...items); }
    replaceChildren(...items) { this.children = items; }
    setAttribute() {}
    addEventListener(name, fn) { this.listeners[name] = fn; }
    emit(name) { this.listeners[name]?.(); }
  }
  const elements = new Proxy({}, { get: (target, key) => target[key] ||= new Element() });
  const context = {
    document: { addEventListener() {}, getElementById: id => elements[id], createElement: () => new Element(), createDocumentFragment: () => new Element() },
    location: { pathname: '/diagnostico/grupos' },
    AvailabilityView: { format() {} }, AvailabilityChart: { Chart: class {} },
    addEventListener() {}, AbortSignal: { timeout() {} },
    fetch: async () => ({ ok: true, json: async () => groups }),
  };
  context.window = context;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/diagnostics.js'), 'utf8'), context);
  await new Promise(resolve => setImmediate(resolve));
  return { elements, rows: () => elements['group-rows'].children[0].children };
}

test('group list limits pages to 50, navigates and searches all pages ignoring accents', async () => {
  const groups = Array.from({ length: 105 }, (_, i) => ({ id: `g${i}`, name: i === 104 ? 'São Paulo' : `Grupo ${i}`, hosts: i + 1 }));
  const ui = await surface(groups), el = ui.elements;
  assert.equal(ui.rows().length, 50);
  assert.equal(el['groups-previous'].disabled, true);
  el['groups-next'].emit('click');
  assert.equal(ui.rows().length, 50);
  assert.match(el['groups-page-status'].textContent, /Página 2 de 3/);
  el['groups-next'].emit('click');
  assert.equal(ui.rows().length, 5);
  assert.equal(el['groups-next'].disabled, true);
  el['groups-previous'].emit('click');
  assert.match(el['groups-page-status'].textContent, /Página 2 de 3/);
  el['group-search'].value = 'sao paulo'; el['group-search'].emit('input');
  assert.equal(ui.rows().length, 1);
  assert.equal(ui.rows()[0].children[0].children[0].href, '/diagnostico/grupos/g104');
  assert.equal(ui.rows()[0].children[1].textContent, 105);
  assert.match(el['groups-page-status'].textContent, /Página 1 de 1/);
  el['group-search'].value = 'ausente'; el['group-search'].emit('input');
  assert.equal(ui.rows().length, 0);
  assert.equal(el['groups-empty'].hidden, false);
  assert.match(el['groups-empty'].textContent, /pesquisa/);
  el['group-search'].value = ''; el['group-search'].emit('input');
  assert.equal(ui.rows().length, 50);
});

test('empty inventory has a helpful empty state and disabled pagination', async () => {
  const { elements } = await surface([]);
  assert.equal(elements['groups-previous'].disabled, true);
  assert.equal(elements['groups-next'].disabled, true);
  assert.equal(elements['groups-empty'].hidden, false);
  assert.match(elements['groups-empty'].textContent, /Nenhum grupo monitorado/);
});
