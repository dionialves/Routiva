const test = require('node:test');
const assert = require('node:assert/strict');
const { rate, format } = require('../../main/resources/static/host-traffic.js');
test('formats rates in kbps through 2 Mbps and Mbps above the threshold', () => {
  assert.equal(format(0), '0 kbps');
  assert.equal(format(1500000), '1.500 kbps');
  assert.equal(format(2000000), '2.000 kbps');
  assert.equal(format(2500000), '2,5 Mbps');
  assert.equal(format(null), 'Aguardando…');
});
test('derives bits per second from bytes rather than formatting cumulative counters as rates', () => {
  assert.equal(rate('1000', '751000', 3000), 2000000);
  assert.equal(rate('9007199254740993', '9007199254741993', 1000), 8000);
  assert.equal(rate('50', '50', 3000), 0);
});
test('counter reset, missing baseline and long gaps require a fresh observation', () => {
  assert.equal(rate('1000', '1', 3000), null);
  assert.equal(rate(undefined, '1000', 3000), null);
  assert.equal(rate('0', '1000', 0), null);
  assert.equal(rate('0', '1000', 16000), null);
});
test('wireless client download uses AP transmitted bytes and upload uses AP received bytes', () => {
  const { wirelessRates } = require('../../main/resources/static/host-traffic.js');
  const first = wirelessRates(null, { '.id':'*1', bytes:'1000,2000' }, 1000);
  assert.equal(first.download, null);
  const next = wirelessRates(first.baseline, { '.id':'*1', bytes:'751000,377000' }, 4000);
  assert.equal(next.download, 2000000);
  assert.equal(next.upload, 1000000);
  assert.equal(wirelessRates(next.baseline, { '.id':'*2', bytes:'751000,377000' }, 7000).download, null);
  assert.equal(wirelessRates(next.baseline, { '.id':'*1', bytes:'1,1' }, 7000).upload, null);
});
test('wifi native traffic rates are accepted while link rates are never used as bandwidth', () => {
  const { wirelessRates } = require('../../main/resources/static/host-traffic.js');
  const direct = wirelessRates(null, { 'tx-bits-per-second':'2500000', 'rx-bits-per-second':'0' }, 1000);
  assert.equal(direct.download, 2500000);
  assert.equal(direct.upload, 0);
  const missing = wirelessRates(null, { 'tx-rate':'130Mbps', 'rx-rate':'54Mbps' }, 1000);
  assert.equal(missing.download, null);
  assert.equal(missing.upload, null);
});
