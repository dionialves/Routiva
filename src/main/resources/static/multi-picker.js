/* Shared searchable multiple selection for incidents and host groups. */
(() => {
  const $ = id => document.getElementById(id);
  window.setupMultiPicker = function(kind, select, allLabel, onChange = () => {}, labels = {}) {
    const listeners = [];
    const listen = (element, type, handler) => {
      element.addEventListener(type, handler);
      listeners.push(() => element.removeEventListener(type, handler));
    };
    const menu = $(`incident-${kind}-picker`);
    const search = $(`incident-${kind}-search`);
    const container = $(`incident-${kind}-options`);
    const panel = container.parentElement;
    const summary = $(`incident-${kind}-summary`);
    const allRow = document.createElement('label');
    allRow.className = 'incident-picker-all';
    const allCheckbox = document.createElement('input'); allCheckbox.type = 'checkbox';
    const allText = document.createElement('span');
    const selectAllText = labels.allText || (kind === 'group' ? 'Selecionar todos os grupos de hosts' : kind === 'name' ? 'Selecionar todos os incidentes' : 'Selecionar todos os hosts');
    allRow.append(allCheckbox, allText); container.append(allRow);
    listen(allCheckbox, 'change', () => {
      for (const { option, label } of entries) {
        if (!label.hidden) option.selected = allCheckbox.checked;
      }
      onChange();
      refresh();
    });
    const entries = [...select.options].map(option => {
      const label = document.createElement('label');
      const checkbox = document.createElement('input'); checkbox.type = 'checkbox';
      const text = document.createElement('span'); text.textContent = option.textContent; text.title = option.textContent;
      label.append(checkbox, text); container.append(label);
      listen(checkbox, 'change', () => {
        option.selected = checkbox.checked;
        onChange();
        refresh();
      });
      return { option, checkbox, label };
    });
    const normalize = value => value.normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase('pt-BR');
    function fitPanel() {
      if (!menu.open || !panel?.getBoundingClientRect) return;
      panel.style.left = '';
      const overflow = panel.getBoundingClientRect().right - document.documentElement.clientWidth + 12;
      if (overflow > 0) panel.style.left = `-${overflow}px`;
    }
    function refresh() {
      const query = normalize(search.value.trim());
      const visible = [];
      for (const { option, checkbox, label } of entries) {
        checkbox.checked = option.selected;
        label.hidden = option.disabled || !normalize(option.textContent).includes(query);
        if (!label.hidden) visible.push(option);
      }
      const focused = document.activeElement;
      const ordered = [...entries].sort((a, b) => Number(b.option.selected) - Number(a.option.selected));
      for (const { label } of ordered) container.append(label);
      if (entries.some(entry => entry.checkbox === focused)) focused.focus();
      const available = entries.filter(entry => !entry.option.disabled);
      const selected = available.filter(entry => entry.option.selected);
      const visibleSelected = visible.filter(option => option.selected).length;
      allText.textContent = query ? 'Selecionar resultados da busca' : selectAllText;
      allCheckbox.disabled = visible.length === 0;
      allCheckbox.checked = visible.length > 0 && visibleSelected === visible.length;
      allCheckbox.indeterminate = visibleSelected > 0 && visibleSelected < visible.length;
      summary.textContent = !selected.length ? allLabel : selected.length === 1
        ? selected[0].option.textContent : `${selected.length} ${labels.selectedLabel || (kind === 'group' ? 'grupos selecionados' : kind === 'name' ? 'incidentes selecionados' : 'hosts selecionados')}`;
      $(`incident-${kind}-empty`).hidden = visible.length !== 0;
      fitPanel();
    }
    if (panel?.getBoundingClientRect) listen(window, 'resize', fitPanel);
    listen(search, 'input', refresh);
    listen(search, 'keydown', event => {
      if (event.key !== 'Enter' || event.isComposing) return;
      event.preventDefault();
      event.stopPropagation();
      refresh();
      let changed = false;
      if (entries.some(entry => !entry.label.hidden)) {
        for (const { option, label } of entries) {
          if (labels.preserveSelection && label.hidden) continue;
          const selected = !label.hidden;
          if (option.selected !== selected) {
            option.selected = selected;
            changed = true;
          }
        }
      }
      if (changed) onChange();
      refresh();
      if (!labels.preserveSelection) {
        menu.open = false;
        menu.querySelector('summary').focus();
      }
    });
    listen(menu, 'toggle', () => {
      if (menu.open) { refresh(); search.focus(); }
    });
    listen(menu, 'keydown', event => {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        if (!menu.open) { event.preventDefault(); menu.open = true; search.focus(); return; }
        const controls = [search, ...container.querySelectorAll('input[type=checkbox]')].filter(input => !input.disabled && !input.closest('[hidden]'));
        const index = controls.indexOf(document.activeElement);
        if (index >= 0) { event.preventDefault(); controls[(index + (event.key === 'ArrowDown' ? 1 : -1) + controls.length) % controls.length].focus(); }
      }
      if (event.key === 'Escape') { menu.open = false; menu.querySelector('summary').focus(); }
    });
    listen(document, 'click', event => { if (!menu.contains(event.target)) menu.open = false; });
    refresh.dispose = () => listeners.forEach(remove => remove());
    refresh(); return refresh;
  };
})();
