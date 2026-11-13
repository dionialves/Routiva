const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

// Minimal DOM surface for exercising the real modal lifecycle without a browser.
class Element {
  constructor(tag = 'div') {
    this.tag = tag; this.children = []; this.dataset = {}; this.style = {};
    this.listeners = {}; this.hidden = false; this.disabled = false; this.value = '';
    this._text = ''; this.classList = { toggle() {} }; this.attributes = {};
  }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  set textContent(value) { this._text = String(value); this.children = []; }
  get cells() { return this.children; }
  get firstElementChild() { return this.children[0] || null; }
  get lastElementChild() { return this.children.at(-1) || null; }
  get nextElementSibling() { return this.parent?.children[this.parent.children.indexOf(this) + 1] || null; }
  append(...children) { children.forEach(child => this.insertBefore(child, null)); }
  insertBefore(child, before) {
    child.remove();
    const index = before ? this.children.indexOf(before) : this.children.length;
    this.children.splice(index, 0, child); child.parent = this;
  }
  remove() { if (this.parent) this.parent.children.splice(this.parent.children.indexOf(this), 1); this.parent = null; }
  replaceChildren(...children) { this.children.forEach(child => { child.parent = null; }); this.children = []; this._text = ''; this.append(...children); }
  addEventListener(type, callback) { (this.listeners[type] ||= []).push(callback); }
  async fire(type) { for (const callback of this.listeners[type] || []) await callback({ preventDefault() {} }); }
  setAttribute(key, value) { this.attributes[key] = value; }
  querySelector(selector) { return selector === 'svg' ? this.svg ||= new Element('svg') : this.segments?.[selector]; }
  focus() { this.focused = true; }
  showModal() { this.open = true; }
  close() { this.open = false; }
}
function harness() {
  const html = fs.readFileSync('src/main/resources/templates/host.html', 'utf8');
  const nodes = {};
  for (const match of html.matchAll(/<[^>]+\bid="([^"]+)"[^>]*>/g)) {
    const element = nodes[match[1]] = new Element();
    element.disabled = /\sdisabled(?:\s|>)/.test(match[0]);
    element.hidden = /\shidden(?:\s|>)/.test(match[0]);
  }
  nodes['latency-packets'].value = '60';
  nodes['latency-sort'].value = nodes['latency-final-sort'].value = 'delta-desc';
  nodes['latency-coverage-bar'].segments = Object.fromEntries(['responding','noReply','noIp','unmeasured'].map(key => [`[data-category="${key}"]`, new Element()]));
  const document = {
    getElementById: id => { assert.ok(nodes[id], `missing DOM id: ${id}`); return nodes[id]; },
    createElement: tag => new Element(tag), createTextNode: text => { const node = new Element(); node.textContent = text; return node; },
    querySelector: selector => selector === '[data-host-id]' ? { dataset: { hostId: '42' } } : null,
    addEventListener() {},
  };
  const requests = [], queue = [], timers = new Map(); let serial = 0;
  const context = {
    document, window: {}, Date, console,
    setTimeout: (callback, delay) => { timers.set(++serial, { callback, delay }); return serial; },
    clearTimeout: id => timers.delete(id),
    fetch: async (url, options) => {
      requests.push({ url, method: options.method });
      assert.ok(queue.length, `unexpected request ${url}`);
      const next = queue.shift();
      return { ok: !next.status, status: next.status || 200,
        headers: { get: () => new Date(next.time).toUTCString() }, json: async () => next.wait ? await next.wait : next.body };
    },
  };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/ap-latency.js', 'utf8'), context);
  return { nodes, queue, timers, requests, async tick(delay) {
    const entry = [...timers].find(([, timer]) => timer.delay === delay);
    assert.ok(entry, `timer ${delay} not found`);
    timers.delete(entry[0]); await entry[1].callback(); await flush();
  }};
}
const flush = async () => { await new Promise(resolve => setImmediate(resolve)); };
const ap = { name: 'AP real', ip: '192.0.2.1', mac: '', warning: null };
const customer = { name: 'João', ip: '192.0.2.2', mac: 'AA:BB:CC:DD:EE:FF', warning: null };
const view = (id = 'one', samples = true) => ({ id, startedAt: 100000, endsAt: 160000, packetCount: 60, completed: false, failed: false, results: [
  { target: ap, samples: samples ? [{ latency: 10, loss: 0 }] : [] },
  { target: customer, samples: samples ? [{ latency: 15, loss: 0 }] : [] },
] });
async function openAndStart(h) {
  h.queue.push({ body: [ap, customer], time: 100000 });
  await h.nodes['ap-latency-open'].fire('click');
  assert.equal(h.nodes['latency-results-tab'].disabled, true);
  h.queue.push({ body: view('one', false), time: 100000 }, { body: view(), time: 101000 });
  await h.nodes['ap-latency-start'].fire('click'); await flush();
}
test('tabs switch once on final server response, preserve results, restart, and never redirect', async () => {
  const h = harness(); await openAndStart(h);
  assert.equal(h.nodes['latency-results-tab'].disabled, true);
  assert.equal(h.nodes['ap-latency-start'].disabled, true);
  h.queue.push({ body: { ...view(), completed: true }, time: 164000 }); await h.tick(1000);
  assert.equal(h.nodes['latency-results-tab'].disabled, false);
  assert.equal(h.nodes['latency-results-panel'].hidden, false);
  assert.equal(h.nodes['latency-start-label'].textContent, 'Executar novamente');
  assert.equal(h.nodes['ap-latency-state'].hidden, true);
  assert.equal(h.nodes['latency-final-results'].children.length, 2);
  assert.equal(h.nodes['latency-final-count'].textContent, '1 clientes avaliados');
  await h.nodes['latency-diagnostic-tab'].fire('click');
  assert.equal(h.nodes['latency-results-panel'].hidden, true);
  assert.equal(h.nodes['latency-start-label'].textContent, 'Start');
  assert.equal(h.nodes['ap-latency-state'].hidden, false);
  await h.nodes['latency-results-tab'].fire('click');
  assert.equal(h.nodes['latency-final-results'].children.length, 2);
  h.queue.push({ status: 404, body: { message: 'expired' }, time: 165000 },
    { body: view('two', false), time: 100000 }, { body: view('two'), time: 101000 });
  await h.nodes['ap-latency-start'].fire('click'); await flush();
  assert.equal(h.nodes['latency-results-tab'].disabled, true);
  assert.equal(h.nodes['latency-diagnostic-panel'].hidden, false);
  assert.equal(h.nodes['latency-final-results'].children.length, 0);
  assert.equal(h.requests.filter(request => request.method === 'POST').length, 2);
});
test('no collection data does not enable completed results', async () => {
  const h = harness(); await openAndStart(h);
  h.queue.push({ body: { ...view('one', false), completed: true }, time: 164000 }); await h.tick(1000);
  assert.equal(h.nodes['latency-results-tab'].disabled, true);
  assert.equal(h.nodes['latency-execution-status'].textContent, 'Erro');
  assert.equal(h.nodes['ap-latency-start'].disabled, false);
});
test('expiration clears results and disables results tab after five minutes', async () => {
  const h = harness(); await openAndStart(h);
  h.queue.push({ body: { ...view(), completed: true }, time: 164000 }); await h.tick(1000);
  await h.tick(296000);
  assert.equal(h.nodes['latency-results-tab'].disabled, true);
  assert.equal(h.nodes['latency-final-results'].children.length, 0);
  assert.equal(h.nodes['latency-execution-status'].textContent, 'Expirado');
});

test('cancelling invalidates a late final response and keeps results disabled', async () => {
  const h = harness();
  h.queue.push({ body: [ap, customer], time: 100000 });
  await h.nodes['ap-latency-open'].fire('click');
  let resolve;
  const wait = new Promise(done => { resolve = done; });
  h.queue.push({ body: view('one', false), time: 100000 }, { wait, time: 164000 });
  await h.nodes['ap-latency-start'].fire('click'); await flush();
  h.queue.push({ status: 204, time: 164000 });
  await h.nodes['ap-latency-close'].fire('click');
  resolve({ ...view(), completed: true }); await flush();
  assert.equal(h.nodes['ap-latency-modal'].open, false);
  assert.equal(h.nodes['latency-results-tab'].disabled, true);
  assert.equal(h.nodes['latency-execution-status'].textContent, 'Cancelado');
  assert.equal(h.timers.size, 0);
});
test('failed read cannot be mistaken for a completed evaluation', async () => {
  const h = harness(); await openAndStart(h);
  h.queue.push({ status: 503, body: { message: 'Comunicação indisponível' }, time: 164000 });
  await h.tick(1000);
  assert.equal(h.nodes['latency-results-tab'].disabled, true);
  assert.equal(h.nodes['latency-execution-status'].textContent, 'Erro');
  assert.equal(h.nodes['ap-latency-state'].textContent, 'Comunicação indisponível');
});

test('results retain all 23 clients and the AP through filtering and tab navigation', async () => {
  const h = harness(); await openAndStart(h);
  const final = { ...view(), completed: true };
  final.results = [final.results[0], ...Array.from({ length: 23 }, (_, index) => ({
    target: { ...customer, name: 'cliente.' + index },
    samples: [{ latency: index === 22 ? null : 15 + index, loss: index === 22 ? 100 : 0 }],
  }))];
  h.queue.push({ body: final, time: 164000 }); await h.tick(1000);
  const table = h.nodes['latency-final-results'];
  assert.equal(table.children.length, 24);
  assert.equal(table.firstElementChild.cells[0].textContent, 'AP real');
  h.nodes['latency-final-search'].value = 'cliente.22';
  await h.nodes['latency-final-search'].fire('input');
  assert.equal(table.children.length, 2);
  h.nodes['latency-final-search'].value = '';
  await h.nodes['latency-final-search'].fire('input');
  await h.nodes['latency-diagnostic-tab'].fire('click');
  await h.nodes['latency-results-tab'].fire('click');
  assert.equal(table.children.length, 24);
  assert.equal(table.firstElementChild.cells[0].textContent, 'AP real');
});

test('selected packet count is sent to backend, locked during execution and restored after completion', async () => {
  const h = harness();
  h.queue.push({ body: [ap, customer], time: 100000 });
  await h.nodes['ap-latency-open'].fire('click');
  h.nodes['latency-packets'].value = '5';
  await h.nodes['latency-packets'].fire('change');
  assert.equal(h.nodes['latency-packet-target'].textContent, '5');
  const running = { ...view(), packetCount: 5 };
  h.queue.push({ body: running, time: 100000 }, { body: running, time: 101000 });
  await h.nodes['ap-latency-start'].fire('click'); await flush();
  assert.equal(h.nodes['latency-packets'].disabled, true);
  assert.ok(h.requests.some(request => request.method === 'POST' && request.url.endsWith('?packets=5')));
  assert.equal(h.nodes['latency-results-tab'].disabled, true);
  assert.equal(h.nodes['latency-remaining'].textContent, '1 / 5');
  const completed = { ...running, completed: true, endsAt: 104000,
    results: running.results.map(result => ({ ...result, samples: Array(5).fill(result.samples[0]) })) };
  h.queue.push({ body: completed, time: 104000 }); await h.tick(1000);
  assert.equal(h.nodes['latency-packets'].disabled, false);
  assert.equal(h.nodes['latency-results-tab'].disabled, false);
  assert.equal(h.nodes['latency-remaining'].textContent, '5 / 5');
});
