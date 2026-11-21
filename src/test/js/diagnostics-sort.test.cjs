const test = require('node:test');
const assert = require('node:assert/strict');
const { compareHostRows } = require('../../main/resources/static/diagnostics.js');
const rows = [
  { id: '3', name: 'Router 10', ip: '10.0.0.10', incidents: 10, ping: null },
  { id: '2', name: 'área', ip: '10.0.0.2', incidents: 2, ping: 12 },
  { id: '1', name: 'Router 2', ip: '10.0.0.1', incidents: 0, ping: 3 }
];
test('group hosts default to natural name order ignoring case and accents', () => {
  assert.deepEqual([...rows].sort(compareHostRows).map(row => row.name), ['área', 'Router 2', 'Router 10']);
});
test('columns sort numeric measurements and inventory addresses in both directions', () => {
  for (const key of ['ip', 'incidents']) {
    assert.deepEqual([...rows].sort((a, b) => compareHostRows(a, b, key)).map(row => row.id), ['1', '2', '3']);
    assert.deepEqual([...rows].sort((a, b) => compareHostRows(a, b, key, -1)).map(row => row.id), ['3', '2', '1']);
  }
});
test('missing measurements stay last in both directions', () => {
  assert.deepEqual([...rows].sort((a, b) => compareHostRows(a, b, 'ping')).map(row => row.id), ['1', '2', '3']);
  assert.deepEqual([...rows].sort((a, b) => compareHostRows(a, b, 'ping', -1)).map(row => row.id), ['2', '1', '3']);
});
