'use strict';

const TableSort = (() => {
  const states = new WeakMap();
  const handlers = new Map();
  const collator = new Intl.Collator('pt-BR', { numeric: true, sensitivity: 'base' });
  const unitFactors = { bps: 1, kbps: 1e3, mbps: 1e6, gbps: 1e9, tbps: 1e12, ms: 1, s: 1000 };

  function value(raw) {
    if (typeof raw === 'number') return Number.isFinite(raw) ? raw : null;
    const text = String(raw ?? '').trim();
    if (!text || /^(?:[—–-]|Indisponível|Sem IP)$/i.test(text)) return null;
    const date = text.match(/^(\d{2})\/(\d{2})\/(\d{2}|\d{4})(?:\s*,?\s+(\d{2}):(\d{2})(?::(\d{2}))?)?$/);
    if (date) {
      const [, day, month, year, hour = 0, minute = 0, second = 0] = date;
      return Date.UTC(year.length === 2 ? 2000 + Number(year) : Number(year), month - 1, day, hour, minute, second);
    }
    const duration = text.match(/^(?:<\s*)?(?:(\d+)\s*d\s*)?(?:(\d+)\s*h\s*)?(?:(\d+)\s*min)?$/i);
    if (duration) return Number(duration[1] ?? 0) * 1440 + Number(duration[2] ?? 0) * 60 + Number(duration[3] ?? 0);
    const metric = text.match(/^([+-]?(?:\d{1,3}(?:\.\d{3})+(?:,\d+)?|\d+(?:[,.]\d+)?))\s*(%|dBm?|ms|s|[kMGT]?bps)?$/i);
    if (metric) {
      const [, number, unit] = metric;
      const localized = number.includes(',') || /^[+-]?\d{1,3}(?:\.\d{3})+$/.test(number);
      return Number(localized ? number.replaceAll('.', '').replace(',', '.') : number)
        * (unitFactors[unit?.toLowerCase()] ?? 1);
    }
    return text;
  }

  function compare(a, b, direction) {
    if (a == null || b == null) return a == null ? Number(b != null) : -1;
    const comparison = typeof a === 'number' && typeof b === 'number'
      ? a - b : typeof a === typeof b ? collator.compare(a, b) : typeof a === 'number' ? -1 : 1;
    return direction === 'descending' ? -comparison : comparison;
  }

  function sort(rows, state, fields) {
    if (!state) return rows;
    const key = fields[state.column];
    return rows.map(row => ({ row, key: value(key(row)) }))
      .sort((a, b) => compare(a.key, b.key, state.direction)).map(entry => entry.row);
  }

  function sortBody(body, state) {
    const records = [];
    const original = [...body.rows];
    for (const row of original) {
      if (row.classList.contains('incident-history-details-row')) {
        records.at(-1)?.push(row);
      } else if (row.cells[state.column]?.colSpan === 1) {
        records.push([row]);
      }
    }
    const ordered = sort(records, { ...state, column: 0 }, [([row]) => {
      const cell = row.cells[state.column];
      const control = cell.querySelector('input:not([type=hidden]), select, [aria-pressed]');
      if (cell.dataset.sortValue != null) return cell.dataset.sortValue;
      if (control?.matches('input[type=checkbox], [aria-pressed]')) {
        return Number(control.checked ?? (control.getAttribute('aria-pressed') === 'true'));
      }
      return control?.selectedOptions?.[0]?.textContent ?? control?.value ?? (cell.textContent.trim()
        || cell.querySelector('[aria-label]')?.getAttribute('aria-label'));
    }]).flat();
    const recordRows = new Set(ordered);
    let index = 0;
    const next = original.map(row => recordRows.has(row) ? ordered[index++] : row);
    if (next.some((row, position) => row !== original[position])) body.append(...next);
  }

  function setup(table) {
    if (states.has(table) || table.classList.contains('system-data-table-static-order')) return;
    const headers = [...(table.tHead?.rows[0]?.cells ?? [])];
    const body = [...table.tBodies].find(section => !section.classList.contains('system-table-state'));
    if (!headers.length || !body) return;
    states.set(table, null);
    if (!handlers.has(body.id)) {
      new MutationObserver(() => {
        const state = states.get(table);
        if (state && !handlers.has(body.id)) sortBody(body, state);
      }).observe(body, { childList: true });
    }
    for (const header of headers) {
      const label = header.textContent.trim() || header.getAttribute('aria-label') || header.title;
      if (!label || header.colSpan !== 1 || header.matches('[data-sort-disabled]')
          || header.querySelector('button, input, a, select')) continue;
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'system-table-sort';
      if (!header.textContent.trim()) button.setAttribute('aria-label', label);
      button.append(...header.childNodes);
      button.addEventListener('click', () => {
        const direction = header.getAttribute('aria-sort') === 'ascending' ? 'descending' : 'ascending';
        for (const column of headers) column.removeAttribute('aria-sort');
        header.setAttribute('aria-sort', direction);
        const state = { column: header.cellIndex, direction };
        states.set(table, state);
        const handler = handlers.get(body.id);
        if (handler) handler(state);
        else {
          sortBody(body, state);
          const pager = [...document.querySelectorAll('[data-pagination-local]')]
            .find(nav => nav.dataset.paginationTable === body.id);
          if (pager) TablePagination.reset(pager.id);
        }
      });
      header.append(button);
    }
  }

  if (typeof MutationObserver !== 'undefined' && document.body) document.addEventListener('DOMContentLoaded', () => {
    const initialize = root => {
      if (root.matches?.('.system-data-table')) setup(root);
      root.querySelectorAll?.('.system-data-table').forEach(setup);
    };
    initialize(document);
    new MutationObserver(changes => {
      for (const change of changes) for (const node of change.addedNodes) initialize(node);
    }).observe(document.body, { childList: true, subtree: true });
  });

  return { bind: (bodyId, handler) => handlers.set(bodyId, handler), sort };
})();
