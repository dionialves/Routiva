const test = require('node:test');
const assert = require('node:assert/strict');
const { ZabbixSelection } = require('../../main/resources/static/zabbix-settings.js');
const a = { id: 'a', name: 'Produção' }, b = { id: 'b', name: 'Filial' };
const shared = { id: '1', name: 'Compartilhado', groups: [a, b] };
const second = { id: '2', name: 'Servidor', groups: [a] };
const data = () => ({ groups: [a, b], hosts: [shared, second], selectedHosts: [], imported: [] });

test('shared hosts have one selection reflected in every group', () => {
  const state = new ZabbixSelection(); state.load(data());
  state.toggleGroup('a', true);
  assert.equal(state.selected.size, 2);
  assert.equal(state.members.get('b').filter(h => state.selected.has(h.id)).length, 1);
  state.toggleGroup('b', false);
  assert.deepEqual([...state.selected], ['2']);
  assert.equal(state.members.get('a').filter(h => state.selected.has(h.id)).length, 1);
});
test('refresh preserves local additions and removals, and leaves new hosts unselected', () => {
  const state = new ZabbixSelection();
  const first = data(); first.selectedHosts = ['1']; state.load(first);
  state.toggle('1', false); state.toggle('2', true);
  const next = data(); next.selectedHosts = ['1']; next.hosts.push({ id: '3', groups: [a] });
  state.load(next);
  assert.deepEqual([...state.selected], ['2']);
  assert.equal(state.changes(), 2);
  state.cancel(); assert.deepEqual([...state.selected], ['1']);
});
test('missing selected hosts stay visible and imported status is independent of selection', () => {
  const state = new ZabbixSelection();
  state.load({ ...data(), selectedHosts: ['gone'], imported: [{ id: 'gone', name: 'Ausente' }] });
  assert.equal(state.members.get('__missing')[0].missing, true);
  assert.equal(state.selected.has('gone'), true);
  state.toggle('gone', false);
  assert.equal(state.imported.has('gone'), true);
});
test('commit uses backend confirmation as saved baseline', () => {
  const state = new ZabbixSelection(); state.load(data()); state.toggleGroup('a', true);
  assert.equal(state.changes(), 2);
  state.commit({ selectedHosts: ['1', '2'], imported: [shared, second] });
  assert.equal(state.changes(), 0);
  state.toggleGroup('a', false); state.cancel(); assert.equal(state.selected.size, 2);
});
test('large inventories deduplicate host IDs and index group memberships', () => {
  const state = new ZabbixSelection(); const large = data();
  large.hosts = Array.from({ length: 10000 }, (_, id) => ({ id: String(id), groups: [a, b] }));
  large.hosts.push(large.hosts[0]); state.load(large);
  state.toggleGroup('a', true); state.toggleGroup('b', true);
  assert.equal(state.selected.size, 10000);
  assert.equal(state.members.get('a').length, 10000);
});
