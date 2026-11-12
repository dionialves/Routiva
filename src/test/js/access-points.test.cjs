const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const flush = () => new Promise(resolve => setImmediate(resolve));
async function surface() {
  class Element {
    constructor() { this.children = []; this.value = ''; this.handlers = {}; this.disabled = false; }
    addEventListener(type, handler) { this.handlers[type] = handler; }
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
    document: { getElementById: id => elements[id], createElement: () => new Element(), createDocumentFragment: () => new Element() },
    fetch: async (url, options) => {
      requests.push({ url, options });
      if (options.method === 'POST') return { ok: !fail, json: async () => fail ? { message: 'Inventário alterado' } : rows };
      return { ok: true, json: async () => url.endsWith('/candidates') ? [{ id: '42', name: '<AP A>', ip: '192.0.2.42' }, { id: '43', name: 'AP B', ip: '192.0.2.43' }] : [] };
    }
  };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/access-points.js', 'utf8'), context);
  await flush();
  return { elements, requests, fail: () => { fail = true; }, click: async id => { await elements['ap-' + id].handlers.click(); await flush(); } };
}
test('arrows physically transfer hosts and only the saved selection is posted', async () => {
  const ui = await surface(), e = ui.elements;
  await ui.click('import');
  assert.equal(e['ap-available'].children.length, 2);
  assert.equal(e['ap-save'].disabled, true);
  e['ap-available'].children[0].selected = true;
  await ui.click('select');
  assert.equal(e['ap-available'].children.length, 1);
  assert.equal(e['ap-selected'].children[0].textContent, '<AP A> · 192.0.2.42');
  e['ap-selected'].children[0].selected = true;
  await ui.click('remove');
  assert.equal(e['ap-selected'].children.length, 0);
  assert.equal(e['ap-available'].children.length, 2);
  e['ap-available'].children[0].selected = true;
  await ui.click('select'); await ui.click('save');
  const posted = ui.requests.find(request => request.options.method === 'POST');
  assert.deepEqual(JSON.parse(posted.options.body), { hostIds: ['42'] });
  assert.equal(e['ap-dialog'].open, false);
  assert.equal(e['ap-rows'].children[0].children[0].children[0].children[0].href, '/ap/42');
  assert.equal(e['ap-rows'].children[0].children[0].children[4].textContent, 0);
});
test('failed save preserves selection and cancel does not persist', async () => {
  const ui = await surface(), e = ui.elements;
  await ui.click('import'); e['ap-available'].children[0].selected = true;
  await ui.click('select'); ui.fail(); await ui.click('save');
  assert.equal(e['ap-dialog'].open, true);
  assert.equal(e['ap-selected'].children.length, 1);
  assert.equal(e['ap-import-state'].textContent, 'Inventário alterado');
  const count = ui.requests.length; await ui.click('cancel');
  assert.equal(ui.requests.length, count);
});
