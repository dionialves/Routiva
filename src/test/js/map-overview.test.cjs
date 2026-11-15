const {test} = require('node:test');
const assert = require('node:assert/strict');
const {summarizeMapNodes} = require('../../main/resources/static/map-overview.js');
const nodes = [{hostId:'a'}, {groupId:'b'}, {hostId:'c'}, {hostId:'d'}, {name:'Decorativo'}];

test('overview counts bound map elements using the selected metric classification', () => {
  const states = ['NORMAL', 'ATENCAO', 'CRITICO', 'DADOS_INSUFICIENTES'];
  const summary = summarizeMapNodes(nodes, 'loss', node => states[nodes.indexOf(node)], true);
  assert.deepEqual(summary, {total:4, data:3, attention:1, unknown:1});
});

test('incident overview respects existing severity states without merging critical into attention', () => {
  const levels = [-1, 2, 5, -2];
  assert.deepEqual(summarizeMapNodes(nodes, 'incidents', node => levels[nodes.indexOf(node)], true),
    {total:4, data:3, attention:1, unknown:1});
});

test('overview shows unavailable counters while loading and actual zeros for an empty loaded map', () => {
  assert.deepEqual(summarizeMapNodes(nodes, 'latency', () => undefined, false),
    {total:4, data:null, attention:null, unknown:null});
  assert.deepEqual(summarizeMapNodes([], 'latency', () => undefined, true),
    {total:0, data:0, attention:0, unknown:0});
});

const {mapNodeStatuses} = require('../../main/resources/static/map-overview.js');
const labels = ['Sem dados', 'Sem incidentes', 'Não classificado', 'Informação', 'Atenção', 'Média', 'Alta', 'Desastre'];
const colors = labels.map((_, i) => 'color-' + i);

test('incident summary only includes states actually painted on bound map nodes', () => {
  const levels = [-2, -1, 4, -1];
  const items = mapNodeStatuses(nodes, 'incidents', node => levels[nodes.indexOf(node)], true, colors, labels);
  assert.deepEqual(items.map(item => [item.label, item.count]), [['Sem dados', 1], ['Sem incidentes', 2], ['Alta', 1]]);
});

test('metric summary omits absent critical state and recomputes when the selected metric changes', () => {
  const latency = ['NORMAL', 'ATENCAO', 'NORMAL', null];
  const loss = ['CRITICO', 'NORMAL', 'NORMAL', 'NORMAL'];
  assert.deepEqual(mapNodeStatuses(nodes, 'latency', node => latency[nodes.indexOf(node)], true, colors, labels)
    .map(item => [item.label, item.count]), [['Normal', 2], ['Atenção', 1], ['Sem dados', 1]]);
  assert.deepEqual(mapNodeStatuses(nodes, 'loss', node => loss[nodes.indexOf(node)], true, colors, labels)
    .map(item => [item.label, item.count]), [['Normal', 3], ['Crítico', 1]]);
  assert.deepEqual(mapNodeStatuses(nodes, 'loss', () => 'CRITICO', false, colors, labels), []);
  assert.deepEqual(mapNodeStatuses([], 'loss', () => 'CRITICO', true, colors, labels), []);
});
