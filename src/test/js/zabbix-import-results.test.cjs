const test = require('node:test');
const assert = require('node:assert/strict');
const { ZabbixImportLog } = require('../../main/resources/static/zabbix-import-results.js');
const entries = [
  { id: '10', name: 'Roteador São Paulo', ip: '', outcome: 'failed', severity: 'error', category: 'Erro de validação', message: 'O coletor exige um endereço IPv4 válido.' },
  { id: '11', name: 'Switch', ip: '10.0.0.2', outcome: 'missing', severity: 'warning', category: 'Origem', message: 'Ausente da origem; dados anteriores preservados.' },
  { id: '12', name: 'OLT', ip: '10.0.0.3', outcome: 'imported', severity: 'success', category: 'Sucesso', message: 'Host importado.' }
];
test('search, category pills and severity combine without discarding records', () => {
  const model = new ZabbixImportLog(); model.load({ state: 'partial', entries });
  model.filter = 'failed'; model.query = 'sao paulo'; model.severity = 'error';
  assert.deepEqual(model.filtered().map(e => e.id), ['10']);
  model.severity = 'success'; assert.equal(model.filtered().length, 0);
  model.filter = 'ignored'; model.query = ''; model.severity = 'all';
  assert.deepEqual(model.filtered().map(e => e.id), ['11']);
  assert.equal(model.count('missing'), 1);
  model.filter = 'imported'; assert.deepEqual(model.filtered().map(e => e.id), ['12']);
  assert.equal(model.entries.length, 3);
});
test('pagination is bounded and sorting compares host names and numeric IDs', () => {
  const model = new ZabbixImportLog();
  model.load({ state: 'completed', entries: Array.from({ length: 1100 }, (_, id) => ({ id: String(id), name: '', outcome: 'updated', severity: 'success', message: 'Atualizado' })) });
  assert.equal(model.visible().entries.length, 25);
  model.page = 500; assert.equal(model.visible().pages, 44); assert.equal(model.page, 43);
  model.descending = true; model.page = 0; assert.equal(model.visible().entries[0].id, '1099');
  model.query = 'not found'; assert.equal(model.visible().total, 0); assert.equal(model.page, 0);
});
test('legacy results retain known errors without inventing success records', () => {
  const model = new ZabbixImportLog();
  model.load({ state: 'partial', imported: 5, ignored: 1, failed: 1, finishedAt: 123,
    errors: [{ id: 'old', reason: 'Ausente da origem; dados anteriores preservados.' }, { id: 'bad', reason: 'O coletor exige um endereço IPv4 válido.' }] },
    [{ id: 'old', name: 'Router', ip: '10.0.0.1' }]);
  assert.equal(model.entries[0].name, 'Router');
  assert.equal(model.count('ignored'), 1); assert.equal(model.count('imported'), 5);
  model.filter = 'imported'; assert.equal(model.filtered().length, 0);
  model.filter = 'failed'; assert.equal(model.filtered()[0].category, 'Erro de validação');
});
