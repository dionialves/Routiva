'use strict';

globalThis.TableState = (() => {
  function elements(id) {
    const body = typeof id === 'string' ? document.getElementById(id) : id;
    const table = body?.closest('table');
    const state = table?.querySelector('.system-table-state');
    return state ? { body, table, state } : null;
  }

  function hasRecords(body) {
    return Array.from(body.rows).some(row =>
      !row.hidden && !row.matches('[data-pagination-off-page], .incident-period-pagination') &&
      (row.matches('.incident-period') || row.cells.length > 1 || row.cells[0]?.colSpan === 1));
  }

  function updateColspan(table, state) {
    const columns = Math.max(1, ...Array.from(table.tHead?.rows ?? [], row =>
      Array.from(row.cells).reduce((total, cell) => total + cell.colSpan, 0)));
    if (state.rows[0].cells[0].colSpan !== columns) state.rows[0].cells[0].colSpan = columns;
  }

  function update(id, status, message) {
    const entry = elements(id);
    if (!entry) return;
    const { body, table, state } = entry;
    const records = hasRecords(body);
    const pending = status === 'loading';
    if (pending && records) TablePagination.keepColumnWidths(body);
    if (!records) TablePagination.keepColumnWidths(body, true);
    updateColspan(table, state);
    table.dataset.tableState = { loading: records ? 'refreshing' : 'loading', done: records ? 'success' : 'empty',
      error: records ? 'stale' : 'error' }[status];
    table.setAttribute('aria-busy', String(pending));
    if (status === 'done' && records) TablePagination.rememberColumnWidths(body);
    state.querySelector('.system-table-description').textContent = status === 'done' && message ? message : 'Não há dados para exibir no momento.';
    state.querySelector('.system-table-error').textContent = message || 'Não foi possível carregar os dados.';
    const stale = 'Os dados apresentados podem estar desatualizados.';
    state.querySelector('.system-table-stale').textContent = message ? `${stale} ${message}` : stale;
    for (const nav of document.querySelectorAll('[data-pagination-table]')) {
      if (nav.dataset.paginationTable === body.id) {
        TablePagination.tableState(nav, pending || status === 'error' && !records,
          !records && (pending || status === 'error'), table.dataset.tableState === 'empty');
      }
    }
  }

  function observe(body) {
    const entry = elements(body);
    if (!entry) return;
    const apply = () => {
      if (['uninitialized', 'empty', 'success'].includes(entry.table.dataset.tableState)) {
        const description = entry.table.dataset.tableState === 'empty' ? entry.state.querySelector('.system-table-description').textContent : undefined;
        update(body, 'done', description);
      }
    };
    new MutationObserver(apply).observe(body, { childList: true });
    apply();
  }

  document.addEventListener('DOMContentLoaded', () => {
    for (const table of document.querySelectorAll('.system-data-table')) {
      for (const body of table.tBodies) {
        if (body.id) observe(body);
      }
    }
  });

  return { loading: id => update(id, 'loading'), done: (id, message) => update(id, 'done', message),
    error: (id, message) => update(id, 'error', message) };
})();
