const { test } = require('node:test');
const assert = require('node:assert/strict');
const { compareHosts } = require('../../main/resources/static/hosts.js');
const host = (id, ip, extra = {}) => ({ id, ip, hostname: id, visibleName: id, templates: [], ...extra });
test('interface sorts IPv4 numerically across octets and keeps absent addresses last', () => {
  const rows = [host('a', '10.0.0.100'), host('b', '10.0.0.9'), host('c', '10.0.1.1'), host('d', '')];
  assert.deepEqual([...rows].sort((a,b) => compareHosts(a,b,'interface')).map(h => h.id), ['b','a','c','d']);
  assert.deepEqual([...rows].sort((a,b) => compareHosts(a,b,'interface',-1)).map(h => h.id), ['c','a','b','d']);
});
test('interface sorts compressed IPv6 and embedded IPv4 by address value', () => {
  const rows = [host('a', '2001:db8::10'), host('b', '2001:db8::2'), host('c', '2001:db8:0:0:0:0:0:2'),
    host('d', '::ffff:192.0.2.10'), host('e', '::ffff:c000:201')];
  assert.deepEqual([...rows].sort((a,b) => compareHosts(a,b,'interface')).map(h => h.id), ['e','d','b','c','a']);
});
test('name sorts by visible name and monitored counts sort numerically', () => {
  const a = host('z', '', { visibleName:'Árvore', monitoredItems:100, monitoredTriggers:2 });
  const b = host('a', '', { visibleName:'Borda', monitoredItems:9, monitoredTriggers:10 });
  assert.ok(compareHosts(a,b,'name') < 0);
  assert.ok(compareHosts(a,b,'items') > 0);
  assert.ok(compareHosts(a,b,'triggers') < 0);
});
