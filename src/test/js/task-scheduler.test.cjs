const { test } = require('node:test');
const assert = require('node:assert/strict');
const { describe, date, cronFrom, cronValues, states, exportText } = require('../../main/resources/static/task-scheduler.js');

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
    this.classList = new Set();
    this.classList.toggle = (key, force) => force ? this.classList.add(key) : this.classList.delete(key);
    this.classList.remove = key => this.classList.delete(key);
    this.dataset = {}; this.value = ''; this.hidden = false; this.disabled = false;
  }
  get valueAsNumber() { return this.value === '' ? NaN : Number(this.value); }
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
  const schedule = id => ({ id, name: 'Agendamento ' + id, taskKey: 'test', taskLabel: 'Avaliação ' + id,
    rule: { frequency: 'DAILY', time: '02:00', weekdays: [], monthDay: 1, intervalMinutes: 30, zone: 'UTC' },
    enabled: true, parameters: {}, nextRun: Date.now() + 86400000 });
  let deferred, fixtureSchedules = empty ? [] : [schedule('one'), schedule('two')];
  const context = {
    document: { getElementById: get, createElement: tag => new Element(tag), querySelector: () => null },
    window: { notify() {} }, Intl, Date, setInterval() {},
    fetch: async (url, options) => {
      requests.push(url);
      let data;
      if (url.endsWith('/api') && options.method === 'GET') data = { defaultZone: 'America/Sao_Paulo', schedules: fixtureSchedules, tasks: [{ key: 'test' }], latest: { one: { state: 'PARTIAL', zone: 'UTC',
        scheduledAt: Date.parse('2026-11-26T02:00:00Z'), startedAt: Date.parse('2026-11-26T02:00:03Z') } } };
      else if (deferred) return deferred(url, options);
      else {
        const execution = { scheduledAt: Date.now(), startedAt: null, finishedAt: null, zone: 'UTC',
          state: 'COMPLETED', id: 'exec-one', taskLabel: 'Avaliação', message: 'Resultado de one' };
        data = url.endsWith('/exec-one')
          ? { execution, items: Array.from({ length: 12 }, (_, index) => ({
            hostName: 'AP ' + (index + 1), state: index === 11 ? 'COMPLETED' : 'PARTIAL', message: 'Sem IP válido.',
          })) }
          : { history: [execution], page: 0, total: 24 };
      }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => data };
    },
  };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/task-scheduler.js', 'utf8'), context);
  await flush();
  return { nodes, fields, requests, defer: fn => { deferred = fn; }, setSchedules: values => { fixtureSchedules = values; }, schedule };
}
test('each schedule opens through the standard task link and history stays in its modal', async () => {
  const { nodes, requests } = await schedulerHarness();
  const rows = nodes['schedule-rows'].children;
  assert.equal(rows.length, 2);
  assert.equal(rows[0].children.length, 6);
  assert.equal(rows[0].children[1].children.length, 0);
  assert.match(rows[0].children[4].textContent, /26\/11\/2026.*02:00:03/);
  assert.equal(rows[0].children[5].dataset.state, 'PARTIAL');
  assert.equal(rows[0].children[5].textContent, 'Concluída com falhas');
  assert.equal(rows[0].children[5].children.length, 0);
  const link = rows[0].children[0].children[0];
  assert.equal(link.tag, 'a'); assert.equal(link.className, 'availability-host-name');
  await link.fire('click');
  assert.equal(nodes['schedule-dialog'].open, true);
  assert.equal(nodes['schedule-data'].hidden, false);
  assert.equal(nodes['schedule-history'].hidden, true);
  await nodes['schedule-history-tab'].fire('click'); await flush();
  assert.ok(requests.at(-1).endsWith('/api/one/history/summary?page=0'));
  assert.equal(nodes['schedule-history'].hidden, false);
  assert.equal(nodes['schedule-data'].hidden, true);
  assert.equal(nodes['schedule-save'].hidden, false);
  assert.equal(nodes['history-pages'], undefined);
  assert.equal(nodes['history-previous'].disabled, true);
  assert.equal(nodes['history-next'].disabled, false);
  assert.equal(nodes['history-page'].textContent, 'Exibindo 1–10 de 24 registros');
  const executionRow = nodes['execution-rows'].children[0];
  assert.equal(executionRow.children.length, 4);
  assert.equal(executionRow.children[3].children[0].children[1].textContent, 'Concluída com sucesso');
  await executionRow.children[0].children[0].fire('click');
  assert.equal(nodes['execution-dialog'].open, true);
  assert.ok(requests.at(-1).endsWith('/api/one/history/exec-one'));
  assert.equal(nodes['execution-result'].textContent, 'Resultado de one');
  const logRows = nodes['execution-log-rows'].children;
  assert.equal(logRows.filter(row => row.className === 'execution-log-row').length, 10);
  assert.equal(logRows[0].children[0].children[0].title, 'AP 1');
  const message = logRows[0].children[2].children[0];
  assert.equal(message.tag, 'span');
  assert.equal(message.title, 'Sem IP válido.');
  await nodes['logs-next'].fire('click');
  assert.equal(nodes['execution-log-rows'].children[0].children[0].children[0].textContent, 'AP 11');
  assert.equal(nodes['logs-page'].textContent, 'Exibindo 11–12 de 12 registros');
  nodes['logs-filter-success'].checked = true;
  await nodes['logs-filter-success'].fire('change');
  assert.equal(nodes['logs-page'].textContent, 'Exibindo 1–1 de 1 registros');
  assert.equal(nodes['execution-log-rows'].children[0].children[0].children[0].textContent, 'AP 12');
  nodes['logs-filter-failure'].checked = true;
  await nodes['logs-filter-failure'].fire('change');
  assert.equal(nodes['logs-page'].textContent, 'Exibindo 1–10 de 12 registros');
  nodes['logs-filter-success'].checked = false;
  await nodes['logs-filter-success'].fire('change');
  assert.equal(nodes['logs-page'].textContent, 'Exibindo 1–10 de 11 registros');
  await nodes['execution-dismiss'].fire('click');
  assert.equal(nodes['execution-dialog'].open, false);
  assert.equal(nodes['schedule-dialog'].open, true);
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
  assert.equal(nodes['schedule-run'].attributes['aria-busy'], 'true');
  assert.equal(nodes['schedule-cancel'].disabled, true);
  assert.equal(nodes['schedule-save'].disabled, true);
  assert.equal(nodes['schedule-delete'].disabled, true);
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
  assert.equal(saved.cron, '0 2 * * *');
  assert.equal(saved.taskKey, 'test'); assert.equal(Object.hasOwn(saved, 'name'), false);
  assert.ok(requests.includes('/configuracao/agendador/api/new/run'));
  assert.equal(nodes['schedule-history'].hidden, false);
  assert.equal(nodes['new-schedule'].disabled, true);
});

test('legacy rules load exact values when possible and leave unsupported fields blank', () => {
  assert.deepEqual(cronValues({ frequency: 'WEEKLY', time: '01:05', weekdays: [7] }), ['5', '1', '*', '*', '0']);
  assert.deepEqual(cronValues({ frequency: 'WEEKLY', time: '01:05', weekdays: [1, 7] }), ['5', '1', '*', '*', '']);
  assert.deepEqual(cronValues({ frequency: 'INTERVAL', intervalMinutes: 30 }), ['', '*', '*', '*', '*']);
  assert.deepEqual(cronValues({ cron: '0 1 15 11 1' }), ['0', '1', '15', '11', '1']);
});

test('Todos disables its input and Valor requires a bounded numeric input', async () => {
  const { nodes, fields } = await schedulerHarness({ empty: true });
  await nodes['new-schedule'].fire('click');
  assert.equal(fields.minute.disabled, false);
  assert.equal(fields.minute.required, true);
  assert.equal(fields.dayOfWeek.disabled, true);
  assert.equal(fields.dayOfWeek.required, false);
  fields.minuteType.value = 'all';
  fields.dayOfWeekType.value = 'value';
  fields.dayOfWeek.value = '0';
  await nodes['schedule-form'].fire('change');
  assert.equal(fields.minute.disabled, true);
  assert.equal(fields.dayOfWeek.required, true);
  assert.equal(cronFrom(nodes['schedule-form']), '* 2 * * 0');
});

test('export includes the execution summary and logs beyond the first page', () => {
  const items = Array.from({ length: 12 }, (_, index) => ({
    hostName: 'Host ' + (index + 1), state: 'COMPLETED', message: 'Backup salvo.',
  }));
  const content = exportText({ taskLabel: 'Backup de hosts', zone: 'UTC', state: 'PARTIAL',
    scheduledAt: Date.parse('2026-11-26T02:00:00Z'), message: '12 hosts processados.' }, items);
  assert.match(content, /26\/11\/2026/);
  assert.match(content, /Concluída com falhas/);
  assert.match(content, /Host 12 — Sucesso — Backup salvo/);
});

test('Salvar on the history tab submits the edited schedule', async () => {
  const harness = await schedulerHarness(), { nodes, fields } = harness;
  await nodes['schedule-rows'].children[0].children[0].children[0].fire('click');
  fields.hour.value = '3';
  await nodes['schedule-history-tab'].fire('click'); await flush();
  let saved;
  harness.defer(async (url, options) => {
    saved = JSON.parse(options.body);
    return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ message: 'Agendamento salvo.' }) };
  });
  await nodes['schedule-form'].fire('submit');
  assert.equal(saved.id, 'one');
  assert.equal(saved.cron, '0 3 * * *');
  assert.equal(nodes['schedule-dialog'].open, false);
});

test('native validation reveals the schedule fields when Salvar is used from history', async () => {
  const { nodes } = await schedulerHarness();
  await nodes['schedule-rows'].children[0].children[0].children[0].fire('click');
  await nodes['schedule-history-tab'].fire('click'); await flush();
  await nodes['schedule-form'].fire('invalid');
  assert.equal(nodes['schedule-data'].hidden, false);
  assert.equal(nodes['schedule-history'].hidden, true);
});
