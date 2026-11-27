'use strict';

const TablePagination = (() => {
  const visiblePages = 5;
  const localPagers = new WeakMap();
  const buttonStates = new WeakMap();
  const savedWidths = new WeakMap();

  function state(page, size, total) {
    const pages = Math.max(1, Math.ceil(total / size));
    const current = Math.min(Math.max(1, page), pages);
    const first = Math.max(1, Math.min(current - 2, pages - visiblePages + 1));
    return {
      current,
      pages,
      start: total ? (current - 1) * size + 1 : 0,
      end: Math.min(current * size, total),
      numbers: Array.from({ length: Math.min(visiblePages, pages) }, (_, offset) => first + offset),
    };
  }

  function measureColumnWidths(table) {
    if (!table || table.querySelector('colgroup') || getComputedStyle(table).tableLayout === 'fixed') return null;
    const header = table.tHead?.rows[0];
    const width = table.getBoundingClientRect().width;
    if (!header || !width || header.cells.length < 2 || [...header.cells].some(cell => cell.colSpan !== 1)) return null;
    return Array.from(header.cells, cell => 100 * cell.getBoundingClientRect().width / width);
  }

  function rememberColumnWidths(body) {
    const table = body.closest('table');
    const widths = measureColumnWidths(table);
    if (widths) savedWidths.set(table, widths);
  }

  function keepColumnWidths(body, savedOnly = false) {
    const table = body?.closest('table');
    if (!table || table.querySelector('colgroup')) return;
    const widths = savedWidths.get(table) ?? (savedOnly ? null : measureColumnWidths(table));
    if (!widths) return;
    const columns = document.createElement('colgroup');
    for (const width of widths) {
      const column = document.createElement('col');
      column.style.width = `${width}%`;
      columns.append(column);
    }
    table.insertBefore(columns, table.tHead);
    table.classList.add('paged-table-fixed');
  }

  function updateCount(bodyId, total) {
    if (!bodyId) return;
    const badge = document.getElementById(`${bodyId}-count`);
    if (!badge) return;
    badge.textContent = `${total} ${total === 1 ? 'encontrado' : 'encontrados'}`;
    badge.hidden = false;
  }

  function render(target, page, size, total, onChange) {
    const nav = typeof target === 'string' ? document.getElementById(target) : target;
    const { current, pages, start, end, numbers } = state(page, size, total);
    const [status, first, previous, ...buttons] = nav.children;
    const [next, last] = buttons.splice(visiblePages);
    const focusedNumber = buttons.find(button => button === document.activeElement);
    const select = targetPage => {
      if (!onChange || current === targetPage) return;
      if (nav.dataset.paginationTable) keepColumnWidths(document.getElementById(nav.dataset.paginationTable));
      return onChange(targetPage);
    };
    status.textContent = total ? `Exibindo ${start}–${end} de ${total} ${nav.dataset.paginationUnit}` : 'Exibindo 0 de 0 registros';
    for (const [button, destination, disabled] of [
      [first, 1, current === 1], [previous, current - 1, current === 1],
      [next, current + 1, current === pages], [last, pages, current === pages]
    ]) {
      buttonStates.set(button, disabled);
      button.disabled = disabled || nav.dataset.paginationLocked === 'true';
      button.onclick = disabled ? null : () => select(destination);
    }
    for (const [index, button] of buttons.entries()) {
      const number = numbers[index];
      button.hidden = number === undefined;
      if (button.hidden) continue;
      button.textContent = String(number);
      buttonStates.set(button, pages === 1);
      button.disabled = pages === 1 || nav.dataset.paginationLocked === 'true';
      button.setAttribute('aria-label', `Página ${number}`);
      if (number === current) button.setAttribute('aria-current', 'page');
      else button.removeAttribute('aria-current');
      button.onclick = () => select(number);
    }
    if (focusedNumber && focusedNumber.textContent !== String(current)) {
      buttons[numbers.indexOf(current)].focus({ preventScroll: true });
    }
    if (!nav.dataset.paginationGrouped && nav.dataset.paginationWithoutTotal !== 'true') updateCount(nav.dataset.paginationTable, total);
    nav.hidden = false;
  }

  function tableState(nav, locked, withoutTotal, empty) {
    nav.dataset.paginationLocked = String(locked);
    nav.dataset.paginationWithoutTotal = String(withoutTotal);
    const bodyId = nav.dataset.paginationTable;
    const badge = bodyId && document.getElementById(`${bodyId}-count`);
    if (badge && withoutTotal) badge.hidden = true;
    if (empty && !withoutTotal) updateCount(bodyId, 0);
    nav.firstElementChild.hidden = withoutTotal;
    if (empty) nav.firstElementChild.textContent = 'Exibindo 0 de 0 registros';
    for (const button of nav.querySelectorAll('button')) {
      button.disabled = locked || (buttonStates.get(button) ?? true);
    }
  }

  function observe(nav) {
    const body = document.getElementById(nav.dataset.paginationTable);
    const size = Number(nav.dataset.paginationLocal);
    let page = 1;
    function update() {
      // A disclosure row belongs to the record immediately above it.
      const records = [];
      for (const row of body.rows) {
        if (row.classList.contains('incident-history-details-row')) {
          records.at(-1)?.push(row);
        } else if (row.cells.length > 1 || row.cells[0]?.colSpan === 1) {
          records.push([row]);
        }
      }
      const { current, start, end } = state(page, size, records.length);
      page = current;
      records.forEach((rows, index) => {
        for (const row of rows) row.toggleAttribute('data-pagination-off-page', index + 1 < start || index >= end);
      });
      render(nav, page, size, records.length, target => { page = target; update(); });
    }
    const reset = () => { page = 1; update(); };
    localPagers.set(nav, reset);
    new MutationObserver(update).observe(body, { childList: true });
    update();
  }

  function reset(id) {
    const nav = document.getElementById(id);
    localPagers.get(nav)?.();
  }

  if (typeof MutationObserver !== 'undefined') {
    document.addEventListener('DOMContentLoaded', () => {
      for (const nav of document.querySelectorAll('[data-pagination-local]')) observe(nav);
    });
  }

  return { render, reset, state, updateCount, tableState, keepColumnWidths, rememberColumnWidths };
})();
