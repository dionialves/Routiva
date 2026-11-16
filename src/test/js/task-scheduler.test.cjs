const { test } = require('node:test');
const assert = require('node:assert/strict');
const { describe, date, states } = require('../../main/resources/static/task-scheduler.js');

test('guided schedule descriptions explain monthly omissions and aligned intervals', () => {
  assert.match(describe({ frequency: 'MONTHLY', monthDay: 31, time: '02:00' }), /somente nos meses/);
  assert.equal(describe({ frequency: 'WEEKLY', weekdays: [1, 7], time: '02:00' }), 'Seg, Dom às 02:00');
  assert.equal(describe({ frequency: 'DAILY', time: '02:00' }), 'Todos os dias às 02:00');
  assert.match(describe({ frequency: 'INTERVAL', intervalMinutes: 30 }), /minuto 00/);
});
test('execution times use schedule zone rather than browser zone', () => {
  const instant = Date.parse('2026-11-16T05:00:00Z');
  assert.match(date(instant, 'America/Sao_Paulo'), /02:00:00/);
  assert.match(date(instant, 'UTC'), /05:00:00/);
  assert.equal(date(null, 'UTC'), '—');
  assert.equal(states.MISSED, 'Horário perdido');
});

const fs = require('node:fs');
const vm = require('node:vm');
class Element {
  constructor(tag = 'div') {
    this.tag = tag; this.children = []; this.listeners = {}; this.attributes = {};
    this.dataset = {}; this.value = ''; this.hidden = false; this.disabled = false;
  }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  setAttribute(key, value) { this.attributes[key] = value; }
  removeAttribute(key) { delete this.attributes[key]; }
  addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
  async fire(type, extra = {}) {
    for (const listener of this.listeners[type] || []) await listener({ preventDefault() {}, ...extra });
  }
  querySelector() { return this.span ||= new Element('span'); }
  querySelectorAll() { return []; }
  focus() { this.focused = true; }
  showModal() { this.open = true; }
  close() { this.open = false; this.fire('close'); }
  reset() {}
  reportValidity() { return true; }
}
const flush = () => new Promise(resolve => setImmediate(resolve));
async function schedulerHarness({ empty = false } = {}) {
  const nodes = {}, fields = {}, requests = [];
  const get = id => nodes[id] ||= new Element();
  get('schedule-form').elements = { namedItem: name => fields[name] ||= new Element() };
  for (const [name, value] of Object.entries({ frequency: 'DAILY', time: '02:00', zone: 'UTC', monthDay: '1', intervalMinutes: '30' }))
    get('schedule-form').elements.namedItem(name).value = value;
  const schedule = id => ({ id, name: 'Agendamento ' + id, taskKey: 'test', taskLabel: 'Avaliação ' + id,
    rule: { frequency: 'DAILY', time: '02:00', weekdays: [], monthDay: 1, intervalMinutes: 30, zone: 'UTC' },
    enabled: true, parameters: {}, nextRun: Date.now() + 86400000 });
  let deferred, fixtureSchedules = empty ? [] : [schedule('one'), schedule('two')];
  const context = {
    document: { getElementById: get, createElement: tag => new Element(tag), querySelector: () => null },
    Intl, Date, setTimeout: () => 1, clearTimeout() {}, setInterval() {},
    fetch: async (url, options) => {
      requests.push(url);
      let data;
      if (url.endsWith('/api') && options.method === 'GET') data = { schedules: fixtureSchedules, tasks: [{ key: 'test' }], latest: {} };
      else if (deferred) return deferred(url, options);
      else data = { history: [{ scheduledAt: Date.now(), startedAt: null, finishedAt: null, zone: 'UTC', state: 'COMPLETED', id: 'exec-one', message: 'Resultado de one' }], page: 0, total: 1, details: { 'exec-one': [{ hostName: 'AP one', state: 'PARTIAL', message: 'Sem IP válido.' }] } };
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => data };
    },
  };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/task-scheduler.js', 'utf8'), context);
  await flush();
  return { nodes, requests, defer: fn => { deferred = fn; }, setSchedules: values => { fixtureSchedules = values; }, schedule };
}
test('each schedule opens through the standard task link and history stays in its modal', async () => {
  const { nodes, requests } = await schedulerHarness();
  const rows = nodes['schedule-rows'].children;
  assert.equal(rows.length, 2);
  assert.equal(rows[0].children.length, 5);
  const link = rows[0].children[0].children[0];
  assert.equal(link.tag, 'a'); assert.equal(link.className, 'availability-host-name');
  await link.fire('click');
  assert.equal(nodes['schedule-dialog'].open, true);
  assert.equal(nodes['schedule-data'].hidden, false);
  assert.equal(nodes['schedule-history'].hidden, true);
  await nodes['schedule-history-tab'].fire('click'); await flush();
  assert.ok(requests.at(-1).endsWith('/api/one/history?page=0'));
  assert.equal(nodes['schedule-history'].hidden, false);
  assert.equal(nodes['schedule-data'].hidden, true);
  assert.equal(nodes['schedule-save'].hidden, true);
  const disclosure = nodes['execution-rows'].children[0].children[4].children[0];
  assert.equal(disclosure.tag, 'details');
  assert.match(disclosure.children[1].children[0].textContent, /AP one: Concluída com falhas/);
  await nodes['schedule-history-tab'].fire('keydown', { key: 'Home' });
  assert.equal(nodes['schedule-data-tab'].focused, true);
  assert.equal(nodes['schedule-data-tab'].attributes['aria-selected'], 'true');
});
test('late history responses cannot replace the selected schedule history', async () => {
  const harness = await schedulerHarness(), { nodes } = harness;
  let resolveOld;
  harness.defer(() => new Promise(resolve => { resolveOld = resolve; }));
  await nodes['schedule-rows'].children[0].children[0].children[0].fire('click');
  await nodes['schedule-history-tab'].fire('click');
  nodes['schedule-dialog'].close();
  await nodes['schedule-rows'].children[1].children[0].children[0].fire('click');
  resolveOld({ ok: true, headers: { get: () => 'application/json' }, json: async () => ({
    history: [{ state: 'FAILED', zone: 'UTC', message: 'Old result' }], page: 0, total: 1,
  }) });
  await flush();
  assert.equal(nodes['execution-rows'].children.length, 0);
  assert.equal(nodes['schedule-title'].textContent, 'Avaliação two');
});

test('run now starts only once and opens the history without saving changes to an existing schedule', async () => {
  const harness = await schedulerHarness(), { nodes, requests } = harness;
  await nodes['schedule-rows'].children[0].children[0].children[0].fire('click');
  let accept;
  harness.defer(url => url.endsWith('/run') ? new Promise(resolve => { accept = resolve; }) : Promise.resolve({
    ok: true, headers: { get: () => 'application/json' }, json: async () => ({ history: [], page: 0, total: 0 }),
  }));
  const pending = nodes['schedule-run'].fire('click');
  await nodes['schedule-run'].fire('click');
  assert.equal(requests.filter(url => url.endsWith('/run')).length, 1);
  accept({ ok: true, headers: { get: () => 'application/json' }, json: async () => ({ message: 'Execução iniciada' }) });
  await pending; await flush();
  assert.equal(nodes['schedule-history'].hidden, false);
  assert.equal(nodes['schedule-run'].disabled, false);
  assert.equal(nodes['new-schedule'].disabled, true);
});

test('running a new activity creates one paused schedule without a name before starting it', async () => {
  const harness = await schedulerHarness({ empty: true }), { nodes, requests } = harness;
  const response = data => ({ ok: true, headers: { get: () => 'application/json' }, json: async () => data });
  let saved;
  harness.defer(async (url, options) => {
    if (url.endsWith('/api')) {
      saved = JSON.parse(options.body);
      harness.setSchedules([{ ...harness.schedule('new'), enabled: false }]);
      return response({ id: 'new', message: 'Salvo' });
    }
    if (url.endsWith('/run')) return response({ executionId: 'execution', message: 'Iniciada' });
    return response({ history: [], page: 0, total: 0 });
  });
  await nodes['new-schedule'].fire('click');
  await nodes['schedule-run'].fire('click'); await flush();
  assert.equal(saved.enabled, false);
  assert.equal(saved.taskKey, 'test'); assert.equal(Object.hasOwn(saved, 'name'), false);
  assert.ok(requests.includes('/configuracao/agendador/api/new/run'));
  assert.equal(nodes['schedule-history'].hidden, false);
  assert.equal(nodes['new-schedule'].disabled, true);
});
