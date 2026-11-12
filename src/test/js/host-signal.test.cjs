const test = require('node:test');
const assert = require('node:assert/strict');
const { policy } = require('../../main/resources/static/host-signal.js');

test('connection diagnosis uses the server severity even when the signal is excellent', () => {
  const p = policy();
  assert.equal(p.category({ signal:'-50', 'avg-signal':'-51', 'quality-severity':'3' }), 3);
  assert.equal(p.signal({ signal:'-50', 'avg-signal':'-51' }), -51);
  assert.equal(p.category({ signal:'-50' }), -1);
  assert.equal(p.category({ 'quality-severity':'' }), -1);
  assert.deepEqual(p.names, ['Normal', 'Atenção', 'Ruim', 'Crítico']);
});

test('distribution counts the original connection diagnosis and excludes disconnected associations', () => {
  const result = policy().summarize([
    { 'quality-severity':'0' }, { 'quality-severity':'3' },
    { 'quality-severity':'2', 'connection-status':'disconnected' },
    { signal:'-65' }, { 'quality-severity':'1' }, { 'quality-severity':'2' },
  ]);
  assert.deepEqual(result, { total:5, valid:4, missing:1, counts:[1, 1, 1, 1] });
});
