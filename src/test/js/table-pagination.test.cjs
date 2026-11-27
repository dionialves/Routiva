const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const { install, pager } = require('./support/table-pagination.cjs');

test('numbered pages move as a window and jump to the first or last page', () => {
  const nav = pager();
  const context = { nav };
  install(context);
  vm.runInContext(`
    let current = 3;
    function show(page) {
      current = page;
      TablePagination.render(nav, page, 10, 87, show);
    }
    show(current);
  `, context);
  const numbers = () => nav.children.slice(3, 8).map(button => button.textContent);
  assert.equal(nav.children[0].textContent, 'Exibindo 21–30 de 87 registros');
  assert.deepEqual(numbers(), ['1', '2', '3', '4', '5']);
  assert.equal(nav.children[5].attributes['aria-current'], 'page');
  nav.children[9].emit();
  assert.deepEqual(numbers(), ['5', '6', '7', '8', '9']);
  assert.equal(nav.children[0].textContent, 'Exibindo 81–87 de 87 registros');
  assert.equal(nav.children[8].disabled, true);
  assert.equal(nav.children[9].disabled, true);
  nav.children[1].emit();
  assert.deepEqual(numbers(), ['1', '2', '3', '4', '5']);
  assert.equal(nav.children[1].disabled, true);
  assert.equal(nav.children[2].disabled, true);
  nav.children[7].emit();
  assert.deepEqual(numbers(), ['3', '4', '5', '6', '7']);
});

test('empty and single-page tables retain a selected 1 with disabled navigation', () => {
  const nav = pager();
  const context = { nav };
  install(context);
  for (const total of [0, 8]) {
    vm.runInContext(`TablePagination.render(nav, 9, 10, ${total}, () => {});`, context);
    assert.equal(nav.children[0].textContent, total ? 'Exibindo 1–8 de 8 registros' : 'Exibindo 0 de 0 registros');
    assert.equal(nav.children[3].attributes['aria-current'], 'page');
    assert.equal(nav.children[3].disabled, true);
    assert.ok(nav.children.slice(4, 8).every(button => button.hidden));
    assert.ok([1, 2, 8, 9].every(index => nav.children[index].disabled));
  }
});

test('local pagination keeps detail rows with their record and resets to the first page', () => {
  const nav = pager();
  const row = (details = false) => ({
    cells: details ? [{ colSpan: 3 }] : [{}, {}, {}],
    classList: { contains(name) { return details && name === 'incident-history-details-row'; } },
    toggleAttribute(name, hidden) { this.hiddenByPage = hidden; },
  });
  const records = [row(), row(true), row(), row(true), row(), row(true)];
  const body = { rows: records, closest() { return null; } };
  nav.dataset.paginationTable = 'rows';
  nav.dataset.paginationLocal = '2';
  const context = {
    document: {
      addEventListener(name, callback) { callback(); },
      querySelectorAll() { return [nav]; },
      getElementById(id) { return id === 'rows' ? body : nav; },
    },
    MutationObserver: class { observe() {} },
  };
  install(context);
  assert.deepEqual(records.map(entry => entry.hiddenByPage), [false, false, false, false, true, true]);
  nav.children[8].emit();
  assert.deepEqual(records.map(entry => entry.hiddenByPage), [true, true, true, true, false, false]);
  vm.runInContext("TablePagination.reset('pager');", context);
  assert.deepEqual(records.map(entry => entry.hiddenByPage), [false, false, false, false, true, true]);
});
