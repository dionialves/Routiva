const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/host.js'), 'utf8');
function report(version = 'run:1', evaluatedAt = Date.now()) {
  const metrics = { healthy: 59, degraded: 0, lost: 1, errors: 0, operational: 98.333, technical: 98.333, loss: 1.667, degradation: 0, latency: 10 };
  return { version, evaluatedAt, lastSampleAt: evaluatedAt - 1, retainedFrom: evaluatedAt - 172800000,
    from: evaluatedAt - 60000, to: evaluatedAt, total: metrics,
    series: [{ at: evaluatedAt - 60000, from: evaluatedAt - 60000, to: evaluatedAt, partial: true, provisional: true, metrics }] };
}
function surface(initial = report()) {
  class Element {
    constructor(name = '') { this.name = name; this.children = []; this.listeners = {}; this.attributes = {}; this.value = ''; this.textContent = ''; this.classList = { toggle() {} }; }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
    setAttribute(key, value) { this.attributes[key] = value; }
    addEventListener(name, fn) { this.listeners[name] = fn; }
    emit(name, event = {}) { return this.listeners[name]?.(event); }
    getBoundingClientRect() { return { width: 800, left: 0 }; }
    querySelector() { return { textContent: this.name }; }
    focus() {}
  }
  const ids = ['hours', 'minute-selection', 'minute-detail', 'refresh-status', 'operational', 'technical', 'loss', 'degradation', 'counts', 'no-data', 'date-range', 'retention-note', 'availability-chart', 'loss-chart', 'latency-chart'];
  const elements = Object.fromEntries(ids.map(id => [id, new Element(id)]));
  for (const el of Object.values(elements)) el.parentElement = new Element('title');
  elements.hours.value = '24'; elements.hours.form = new Element('form');
  const requests = [], intervals = [], timeouts = new Map(), observers = [];
  let serial = 0;
  const window = { hostReport: initial, hostId: 'h/1', location: { href: 'http://localhost/hosts/h?hours=24' }, history: { replaceState(_a, _b, url) { window.location.href = String(url); } } };
  vm.runInNewContext(source, {
    window, document: { getElementById: id => elements[id], createElementNS: (_ns, name) => new Element(name) },
    URL, AbortController, Date, Intl,
    ResizeObserver: class { constructor(fn) { this.fn = fn; this.active = false; observers.push(this); } observe() { this.active = true; } disconnect() { this.active = false; } },
    setInterval(fn, ms) { intervals.push({ fn, ms }); },
    setTimeout(fn, ms) { timeouts.set(++serial, { fn, ms }); return serial; }, clearTimeout(id) { timeouts.delete(id); },
    fetch(url, options) { return new Promise((resolve, reject) => {
      options.signal.addEventListener('abort', () => reject(new Error('timeout')));
      requests.push({ url, options, resolve, reject });
    }); },
  });
  return { elements, requests, intervals, timeouts, observers, window,
    tick() { return intervals[0].fn(); },
    choose(value) { elements.hours.value = value; elements.hours.emit('change'); },
    respond(index, body, ok = true) { requests[index].resolve({ ok, json: async () => body }); },
  };
}
const flush = () => new Promise(resolve => setImmediate(resolve));

test('initial page uses embedded snapshot; polls every 10 seconds without overlapping or repainting same version', async () => {
  const ui = surface();
  assert.equal(ui.requests.length, 0);
  assert.equal(ui.intervals[0].ms, 10000);
  const svg = ui.elements['latency-chart'].children[0];
  const first = ui.tick(); await ui.tick();
  assert.equal(ui.requests.length, 1);
  assert.match(ui.requests[0].url, /h%2F1\/availability\?hours=24$/);
  assert.equal(ui.requests[0].options.cache, 'no-store');
  ui.respond(0, report()); await first;
  assert.equal(ui.elements['latency-chart'].children[0], svg);
  assert.equal(ui.timeouts.size, 0);
});

test('period changes discard older response and fetch selected period without navigation', async () => {
  const ui = surface(); const first = ui.tick();
  ui.choose('1'); ui.choose('48');
  assert.equal(ui.requests.length, 1);
  ui.respond(0, report('wrong')); await first;
  assert.equal(ui.requests.length, 2);
  assert.match(ui.requests[1].url, /hours=48$/);
  assert.match(ui.window.location.href, /hours=48/);
  const next = report('run:2'); next.total = { ...next.total, healthy: 90 };
  ui.respond(1, next); await flush();
  assert.match(ui.elements.counts.textContent, /90 saudáveis/);
  assert.equal(ui.observers.filter(o => o.active).length, 3);
  for (const id of ['latency-chart', 'loss-chart', 'availability-chart']) assert.equal(ui.elements[id].children.length, 1);
});

test('failed request retains data and a later poll recovers; stalled request times out', async () => {
  const ui = surface(); const before = ui.elements.counts.textContent;
  const first = ui.tick(); ui.respond(0, {}, false); await first;
  assert.equal(ui.elements.counts.textContent, before);
  assert.match(ui.elements['refresh-status'].textContent, /Falha ao consultar/);
  const second = ui.tick(); ui.respond(1, report('run:2')); await second;
  assert.match(ui.elements['refresh-status'].textContent, /Indicadores calculados/);
  const third = ui.tick(); [...ui.timeouts.values()][0].fn(); await third;
  assert.match(ui.elements['refresh-status'].textContent, /Falha ao consultar/);
  const fourth = ui.tick(); assert.equal(ui.requests.length, 4);
  ui.respond(3, report('run:3')); await fourth;
});

test('shows loss marker even without latency and distinguishes empty buckets with accessible details', () => {
  const data = report();
  data.series[0].metrics = { ...data.total, healthy: 0, lost: 60, latency: null, loss: 100, technical: 0 };
  const ui = surface(data);
  const svg = ui.elements['latency-chart'].children[0];
  const loss = svg.children.find(el => el.attributes['aria-label'] === 'Minutos com perda de pacotes');
  assert.match(loss.attributes.d, /v8/);
  assert.match(ui.elements['minute-detail'].textContent, /60 perdidos/);
  assert.match(ui.elements['minute-selection'].attributes['aria-valuetext'], /sem resposta/);
  data.series[0].metrics = { ...data.total, healthy: 0, lost: 0, errors: 1, latency: null, technical: null, loss: null };
  const empty = surface(data);
  assert.match(empty.elements['minute-detail'].textContent, /Sem dados elegíveis/);
});

test('cold start and stale publication are explicit', () => {
  const cold = surface(report('run:0', null));
  assert.match(cold.elements['refresh-status'].textContent, /Carregando indicadores/);
  assert.equal(cold.elements['minute-selection'].disabled, true);
  const stale = surface(report('run:1', Date.now() - 40000));
  assert.match(stale.elements['refresh-status'].textContent, /Publicação desatualizada/);
});

test('an inspected older minute stays selected when a new publication arrives', async () => {
  const data = report();
  const first = data.series[0];
  data.series.push({ ...first, at: first.at + 60000, from: first.to, to: first.to + 60000 });
  data.to += 60000;
  const ui = surface(data);
  ui.elements['minute-selection'].value = '0';
  ui.elements['minute-selection'].emit('input');
  const pending = ui.tick();
  ui.respond(0, { ...data, version: 'run:2' }); await pending;
  assert.equal(ui.elements['minute-selection'].value, '0');
});
