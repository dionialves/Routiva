const { test } = require('node:test');
const assert = require('node:assert/strict');
const { statistics } = require('../../main/resources/static/ap-latency.js');
test('latency averages exclude losses and collection failures; loss excludes collection failures', () => {
  assert.deepEqual(statistics([
    { latency: 0, loss: 0 }, { latency: 10, loss: 0 },
    { latency: null, loss: 100 }, { latency: null, loss: null },
  ]), { mean: 5, min: 0, max: 10, loss: 100 / 3, replies: 2, total: 4 });
});
test('missing samples and unreachable clients never become zero latency', () => {
  assert.equal(statistics([]).mean, null);
  assert.equal(statistics([]).loss, null);
  assert.equal(statistics([{ latency: null, loss: 100 }]).mean, null);
  assert.equal(statistics([{ latency: null, loss: 100 }]).loss, 100);
});

const { summarize } = require('../../main/resources/static/ap-latency.js');
const result = (name, samples, warning = null) => ({ target: { name, warning }, samples });
const point = latency => ({ latency, loss: latency === null ? 100 : 0 });
test('summary weights each responding client equally and ranks the three largest differences', () => {
  const summary = summarize([
    result('AP', [point(10)]),
    result('A', [point(20), point(20), point(20)]),
    result('B', [point(100)]),
    result('C', [point(30)]),
    result('D', [point(5)]),
    result('Offline', [point(null)]),
    result('Sem IP', [], 'Sem IPv4'),
    result('Erro', [{ latency: null, loss: null }]),
  ]);
  assert.equal(summary.mean, 38.75);
  assert.equal(summary.delta, 28.75);
  assert.equal(summary.measured, 4);
  assert.equal(summary.noReply, 1);
  assert.equal(summary.unavailable, 2);
  assert.equal(summary.loss, 100 / 7);
  assert.deepEqual(summary.worst.map(client => client.target.name), ['B', 'C', 'A']);
  assert.equal(summary.clients.find(client => client.target.name === 'D').delta, -5);
});
test('AP without responses disables differences and ranking but keeps client measurements', () => {
  const summary = summarize([result('AP', [point(null)]), result('A', [point(0)])]);
  assert.equal(summary.mean, 0);
  assert.equal(summary.delta, null);
  assert.deepEqual(summary.worst, []);
});
test('empty results do not fabricate latency or loss', () => {
  const summary = summarize([]);
  assert.equal(summary.mean, null);
  assert.equal(summary.delta, null);
  assert.equal(summary.loss, null);
  assert.equal(summary.measured, 0);
  assert.deepEqual(summary.worst, []);
});

const { classify, selectClients } = require('../../main/resources/static/ap-latency.js');
const client = (name, mean, delta, loss = 0) => ({
  target: { name, ip: '192.0.2.12', mac: 'AA:BB:CC:DD:EE:FF', warning: null },
  stats: { mean, delta, loss, total: 10, replies: mean === null ? 0 : 10 }, delta,
});
test('interpretation considers loss, boundaries, missing reference and negative variation', () => {
  assert.equal(classify(client('Low', 12, 2)).label, 'Normal');
  assert.equal(classify(client('Medium', 15, 5)).label, 'Atenção');
  assert.equal(classify(client('High', 16, 6)).label, 'Crítico');
  assert.equal(classify(client('Loss', 10, 0, 3)).label, 'Atenção');
  assert.equal(classify(client('Negative', 8, -2)).label, 'Normal');
  assert.equal(classify(client('Offline', null, null, 100)).label, 'Sem resposta');
  assert.equal(classify(client('No reference', 20, null)).label, 'Sem referência');
  const pending = client('Pending', null, null, null);
  pending.stats.total = 0;
  assert.equal(classify(pending).label, 'Aguardando');
  assert.equal(classify(pending, true).label, 'Sem coleta');
  pending.target.warning = 'Consulta IXC falhou';
  assert.equal(classify(pending).label, 'Sem IP válido');
});
test('client sorting uses numbers, places unavailable values last and preserves source order', () => {
  const clients = [client('A', 12, 2), client('B', 20, 10), client('C', null, null, 100)];
  assert.deepEqual(selectClients(clients, '', false, 'delta-desc').map(c => c.target.name), ['B', 'A', 'C']);
  assert.deepEqual(selectClients(clients, '', false, 'delta-asc').map(c => c.target.name), ['A', 'B', 'C']);
  assert.deepEqual(selectClients(clients, '', false, 'loss').map(c => c.target.name), ['C', 'A', 'B']);
  assert.deepEqual(selectClients(clients, '', true, 'mean').map(c => c.target.name), ['C']);
  assert.deepEqual(clients.map(c => c.target.name), ['A', 'B', 'C']);
});
test('search matches names without accents, IP and MAC without changing measurements', () => {
  const clients = [client('João', 12, 2)];
  for (const query of ['joao', 'JOÃO', '192.0.2', 'aa:bb'])
    assert.equal(selectClients(clients, query, false, 'name').length, 1);
  assert.equal(selectClients(clients, 'inexistente', false, 'name').length, 0);
  assert.equal(clients[0].stats.mean, 12);
});

const { coverageOf, positiveTop, completionConfirmed } = require('../../main/resources/static/ap-latency.js');
test('coverage categories account for every client, including failed collection and missing IP', () => {
  const invalid = client('No IP', null, null, null);
  invalid.target.warning = 'Sem IP';
  const coverage = coverageOf([
    client('Reply', 0, 0), client('Offline', null, null, 100), invalid,
    client('Collection error', null, null, null),
  ]);
  assert.deepEqual(coverage, { total: 4, responding: 1, noReply: 1, noIp: 1, unmeasured: 1 });
  assert.equal(coverage.responding + coverage.noReply + coverage.noIp + coverage.unmeasured, coverage.total);
});
test('final ranking excludes negative, zero, invalid and missing measurements', () => {
  assert.deepEqual(positiveTop([
    client('Negative', 1, -2), client('Zero', 1, 0), client('High', 10, 7),
    client('Low', 6, 3), client('Offline', null, null, 100),
  ]).map(c => c.target.name), ['High', 'Low']);
});
test('only explicit backend completion confirms packet evaluation', () => {
  assert.equal(completionConfirmed({ endsAt: 0, completed: false }), false);
  assert.equal(completionConfirmed({ completed: true, failed: false }), true);
  assert.equal(completionConfirmed({ completed: true, failed: true }), false);
});
const { packetProgress } = require('../../main/resources/static/ap-latency.js');
test('packet progress follows the slowest valid destination and ignores clients without IP', () => {
  const results = [result('AP', Array(5).fill(point(1))), result('Client', Array(3).fill(point(null))), result('No IP', [], 'Sem IP')];
  assert.equal(packetProgress({ packetCount: 5, results }), 3);
});
