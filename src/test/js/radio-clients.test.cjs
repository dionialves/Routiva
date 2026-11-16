const { test } = require('node:test');
const assert = require('node:assert/strict');
const { format } = require('../../main/resources/static/radio-clients.js');
const collection = { host_id:'AP 1', observed_from:1000, observed_to:12000, mac:'AA:BB:CC:DD:EE:FF',
  data:{ metrics:{ signal:{ mean:-70, count:2 } }, observedLogin:null } };
const { tableValues, compareClients, filterClients } = require('../../main/resources/static/radio-clients.js');
const client = (id, severity, signal, ap = 'ap1') => ({ id, login:id, lastSeen:{ mac:id, host_id:ap }, latest:{ ...collection, host_id:ap, data:{ ...collection.data, connectionSeverity:severity, metrics:{ signal:{ mean:signal }, 'signal-to-noise':{ mean:30 }, 'tx-ccq':{ mean:95 } } } } });
test('table separates wireless metrics and missing latency from zero packet loss', () => {
  const c = client('alice', 0, -60); c.latest.latency = { data:{ latency:null, loss:0 } };
  const v = tableValues(c); assert.equal(v.snr,30); assert.equal(v.ccq,95); assert.equal(v.latency,null); assert.equal(v.loss,0);
  assert.equal(tableValues({ id:'p', provisional_mac:'AA' }).signal,null);
});
test('client AP and status multi-selection filters combine', () => {
  const rows = [client('a',0,-60), client('b',1,-70), client('c',3,-90,'ap2'), client('d',2,-80)];
  assert.deepEqual(filterClients(rows, new Set(['a','b','c']), new Set(['ap1']), new Set(['normal','attention'])).map(c => c.id), ['a','b']);
  assert.equal(tableValues(rows[3]).status, 'attention');
  assert.equal(tableValues(client('unknown', null, null)).status,'unknown');
});
test('numeric sorting works in both directions and keeps missing values last', () => {
  const rows = [client('a',0,-60), client('b',3,-90), client('c',null,null)];
  assert.deepEqual([...rows].sort((a,b) => compareClients(a,b,'signal',1)).map(c => c.id),['b','a','c']);
  assert.deepEqual([...rows].sort((a,b) => compareClients(a,b,'signal',-1)).map(c => c.id),['a','b','c']);
});

test('connection label preserves the AP diagnosis independently from signal', () => {
  const c = client('alice', 2, -40);
  c.latest.data.connectionDiagnosis = { label:'Ruim', reason:'SNR comprometido.', at:1000 };
  assert.equal(tableValues(c).statusLabel, 'Ruim');
  assert.equal(tableValues(c).status, 'attention');
});

const { overviewFields, historyValues } = require('../../main/resources/static/radio-clients.js');
test('overview contains only the requested identity fields', () => {
  const c = client('alice', 0, -60); c.latest.data['radio-name'] = 'Radio Casa';
  const values = overviewFields(c);
  assert.deepEqual(values.map(([label]) => label), ['AP','Data da coleta','MAC','Interface','Login','Nome do rádio']);
  assert.equal(Object.fromEntries(values)['Nome do rádio'], 'Radio Casa');
});
test('history presents one row of metrics and keeps total loss distinct from no test', () => {
  const c = { ...collection, latency:{ data:{ latency:null, loss:100 } } };
  const values = historyValues(c);
  assert.equal(values.length,9); assert.equal(values[3],'-70 dBm');
  assert.equal(values[7],'—'); assert.equal(values[8],'100 %');
  assert.equal(historyValues(collection)[8],'—');
});

test('collection details retain information omitted from the compact history', () => {
  const { collectionFields } = require('../../main/resources/static/radio-clients.js');
  const details = Object.fromEntries(collectionFields({ ...collection, data:{ ...collection.data, uptime:'2h', 'radio-name':'Casa', distance:'3' } }));
  assert.equal(details['Nome do rádio'],'Casa'); assert.equal(details['Tempo conectado'],'2h');
  assert.equal(details['Distância'],'3 km'); assert.equal(details['Amostras de Sinal'],2);
});

test('client pagination limits pages to 50 and clamps after filtering', () => {
  const { paginateClients } = require('../../main/resources/static/radio-clients.js');
  const rows = Array.from({ length:133 }, (_, id) => ({ id }));
  const first = paginateClients(rows,1); assert.equal(first.rows.length,50); assert.equal(first.start,1); assert.equal(first.end,50); assert.equal(first.previous,false); assert.equal(first.next,true);
  const last = paginateClients(rows,3); assert.equal(last.rows.length,33); assert.equal(last.start,101); assert.equal(last.end,133); assert.equal(last.next,false);
  const filtered = paginateClients(rows.slice(0,12),3); assert.equal(filtered.page,1); assert.equal(filtered.rows.length,12);
  const empty = paginateClients([],1); assert.equal(empty.start,0); assert.equal(empty.end,0); assert.equal(empty.next,false);
});

const { observationPeriod, icmpDetails } = require('../../main/resources/static/radio-clients.js');
test('observation period retains dates when the collection crosses midnight', () => {
  const first = new Date(2026, 10, 15, 23, 59).getTime(), next = new Date(2026, 10, 16, 0, 1).getTime();
  assert.match(observationPeriod({ observed_from:first, observed_to:next }), /15\/11\/2026/);
  assert.match(observationPeriod({ observed_from:first, observed_to:next }), /16\/11\/2026/);
  assert.doesNotMatch(observationPeriod({ observed_from:first - 1000, observed_to:first }), /2026/);
});
test('ICMP distinguishes no test from partial data and retains real zero values', () => {
  assert.equal(icmpDetails(collection).message,'Teste ICMP não realizado nesta coleta.');
  const partial = icmpDetails({ ...collection, latency:{ tested_at:1000, data:{ loss:0, replies:0 } } });
  assert.equal(partial.message,'');
  assert.equal(Object.fromEntries(partial.fields)['Perda de pacote'],'0 %');
  assert.equal(Object.fromEntries(partial.fields).Respostas,0);
  assert.ok(partial.unavailable.includes('Latência'));
  const loss = icmpDetails({ latency:{ data:{ loss:100, latency:null, lost:5, replies:0 } } });
  assert.equal(Object.fromEntries(loss.fields)['Perda de pacote'],'100 %');
  assert.ok(loss.unavailable.includes('Latência'));
});

test('host group selection combines with AP, client and connection and accepts any selected membership', () => {
  const a = { ...client('a', 0, -60, 'ap1'), groups: [{ id: 'g1', name: 'Norte' }, { id: 'g2', name: 'Rádio' }] };
  const b = { ...client('b', 0, -60, 'ap2'), groups: [{ id: 'g3', name: 'Sul' }] };
  const c = client('c', 0, -60, 'ap1');
  assert.deepEqual(filterClients([a,b,c], new Set(), new Set(), new Set(), new Set(['g2','g3'])).map(c => c.id), ['a','b']);
  assert.deepEqual(filterClients([a,b,c], new Set(['a','b']), new Set(['ap1']), new Set(['normal']), new Set(['g2'])).map(c => c.id), ['a']);
  assert.equal(filterClients([a,b,c], new Set(), new Set(['ap2']), new Set(), new Set(['g1'])).length, 0);
  assert.equal(filterClients([a,b,c], new Set(), new Set(), new Set(), new Set()).length, 3);
});
