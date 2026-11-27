const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function fixture() {
  const labels = new Map();
  const nav = { dataset: { paginationTable: 'records' } };
  const stateCell = { colSpan: 1 };
  const stateBody = {
    rows: [{ cells: [stateCell] }],
    querySelector(selector) {
      if (!labels.has(selector)) labels.set(selector, {});
      return labels.get(selector);
    },
  };
  const table = {
    dataset: {},
    tHead: { rows: [{ cells: [{ colSpan: 1 }, { colSpan: 2 }] }] },
    querySelector() { return stateBody; },
    setAttribute(name, value) { this[name] = value; },
  };
  const body = { id: 'records', rows: [], closest() { return table; } };
  const context = {
    document: { getElementById: () => body, querySelectorAll: () => [nav], addEventListener() {} },
    TablePagination: {
      keepColumnWidths(body, savedOnly) { if (!savedOnly) table.widthsPreserved = true; },
      rememberColumnWidths() {},
      tableState(el, locked, withoutTotal, empty) { Object.assign(el, { locked, withoutTotal, empty }); },
    },
  };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/table-state.js', 'utf8'), context);
  return { body, table, nav, stateCell, labels, api: context.TableState };
}

test('load, empty and failure span the real columns and report zero only on empty success', () => {
  const { table, nav, stateCell, labels, api } = fixture();
  api.loading('records');
  assert.equal(table.dataset.tableState, 'loading');
  assert.equal(table['aria-busy'], 'true');
  assert.equal(stateCell.colSpan, 3);
  assert.deepEqual([nav.locked, nav.withoutTotal, nav.empty], [true, true, false]);
  api.error('records', 'Sem comunicação.');
  assert.equal(table.dataset.tableState, 'error');
  assert.equal(labels.get('.system-table-error').textContent, 'Sem comunicação.');
  assert.deepEqual([nav.locked, nav.withoutTotal, nav.empty], [true, true, false]);
  api.done('records');
  assert.equal(table.dataset.tableState, 'empty');
  assert.deepEqual([nav.locked, nav.withoutTotal, nav.empty], [false, false, true]);
});

test('a background refresh retains records and turns errors into an outdated-data warning', () => {
  const { body, table, nav, labels, api } = fixture();
  const row = { cells: [{ colSpan: 1 }], matches: () => false };
  body.rows.push(row);
  api.done('records');
  assert.equal(table.dataset.tableState, 'success');
  api.loading('records');
  assert.equal(table.dataset.tableState, 'refreshing');
  assert.deepEqual([nav.locked, nav.withoutTotal], [true, false]);
  assert.equal(table.widthsPreserved, true);
  api.error('records');
  assert.equal(table.dataset.tableState, 'stale');
  assert.equal(body.rows[0], row);
  assert.match(labels.get('.system-table-stale').textContent, /desatualizados/);
  api.done('records');
  assert.equal(table.dataset.tableState, 'success');
  assert.equal(nav.locked, false);
});
