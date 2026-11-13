/* Shared clear action for page queries; never reset editable settings or import selections. */
document.addEventListener('DOMContentLoaded', () => {
  const storageByPage = {
    '/disponibilidade': ['routiva.availability.period'],
    '/incidentes': ['routiva.incidents.filters'],
    '/ap': [],
    '/diagnostico/grupos': ['routiva.diagnostics.groups.filters'],
  };
  document.querySelectorAll('.query-filter-bar, .zabbix-filters, .import-results-tools').forEach(bar => {
    const row = bar.matches('.query-filter-row') ? bar : bar.querySelector('.query-filter-row') || bar;
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'system-button system-button-icon-only query-clear-filters';
    button.title = 'Limpar todos os filtros';
    button.setAttribute('aria-label', button.title);
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', 'M3 4h18l-7 8v7l-4 2v-9L3 4m13 12 5 5m0-5-5 5');
    svg.append(path); button.append(svg); row.append(button);
    button.addEventListener('click', () => {
      const pathname = location.pathname;
      const storageKeys = storageByPage[pathname];
      if (storageKeys || pathname.startsWith('/diagnostico/grupos/')) {
        for (const key of storageKeys || ['routiva.diagnostics.period']) {
          try { localStorage.removeItem(key); } catch {}
        }
        // A fresh query also clears exact event links, pagination and picker state.
        location.assign(pathname);
        return;
      }
      const controls = [...row.querySelectorAll('input[type="search"], input[type="text"], select')];
      for (const control of controls) {
        if (control.tagName === 'SELECT') {
          for (const option of control.options) option.selected = option.defaultSelected;
          if (!control.multiple && control.selectedIndex < 0) control.selectedIndex = 0;
        } else control.value = '';
      }
      for (const control of controls) {
        control.dispatchEvent(new Event(control.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true }));
      }
      row.dispatchEvent(new Event('query-filters-cleared', { bubbles: true }));
    });
  });
});
