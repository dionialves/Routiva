/* Shared searchable multiple selection for incidents and host groups. */
(() => {
  const $ = id => document.getElementById(id);
  window.setupMultiPicker = function(kind, select, allLabel, onChange = () => {}) {
    const listeners = [];
    const listen = (element, type, handler) => {
      element.addEventListener(type, handler);
      listeners.push(() => element.removeEventListener(type, handler));
    };
    const menu = $(`incident-${kind}-picker`);
    const search = $(`incident-${kind}-search`);
    const container = $(`incident-${kind}-options`);
    const summary = $(`incident-${kind}-summary`);
    const allRow = document.createElement('label');
    allRow.className = 'incident-picker-all';
    const allCheckbox = document.createElement('input'); allCheckbox.type = 'checkbox';
    const allText = document.createElement('span');
    allText.textContent = kind === 'group' ? 'Todos os grupos de hosts' : kind === 'name' ? 'Todos os incidentes' : 'Todos os Hosts';
    allRow.append(allCheckbox, allText); container.append(allRow);
    listen(allCheckbox, 'change', () => {
      for (const option of select.options) {
        if (!option.disabled) option.selected = allCheckbox.checked;
      }
      onChange();
      refresh();
    });
    const entries = [...select.options].map(option => {
      const label = document.createElement('label');
      const checkbox = document.createElement('input'); checkbox.type = 'checkbox';
      const text = document.createElement('span'); text.textContent = option.textContent;
      label.append(checkbox, text); container.append(label);
      listen(checkbox, 'change', () => {
        option.selected = checkbox.checked;
        onChange();
        refresh();
      });
      return { option, checkbox, label };
    });
    const normalize = value => value.normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase('pt-BR');
    function refresh() {
      const query = normalize(search.value.trim());
      let visible = 0;
      for (const { option, checkbox, label } of entries) {
        checkbox.checked = option.selected;
        label.hidden = option.disabled || !normalize(option.textContent).includes(query);
        if (!label.hidden) visible++;
      }
      const available = entries.filter(entry => !entry.option.disabled);
      const selected = available.filter(entry => entry.option.selected);
      allCheckbox.disabled = available.length === 0;
      allCheckbox.checked = available.length > 0 && selected.length === available.length;
      allCheckbox.indeterminate = selected.length > 0 && selected.length < available.length;
      summary.textContent = !selected.length ? allLabel : selected.length === 1
        ? selected[0].option.textContent : `${selected.length} ${kind === 'group' ? 'grupos selecionados' : kind === 'name' ? 'incidentes selecionados' : 'hosts selecionados'}`;
      $(`incident-${kind}-empty`).hidden = visible !== 0;
    }
    listen(search, 'input', refresh);
    listen(search, 'keydown', event => {
      if (event.key !== 'Enter' || event.isComposing) return;
      event.preventDefault();
      event.stopPropagation();
      refresh();
      let changed = false;
      if (entries.some(entry => !entry.label.hidden)) {
        for (const { option, label } of entries) {
          const selected = !label.hidden;
          if (option.selected !== selected) {
            option.selected = selected;
            changed = true;
          }
        }
      }
      if (changed) onChange();
      refresh();
      menu.open = false;
      menu.querySelector('summary').focus();
    });
    listen(menu, 'toggle', () => {
      if (menu.open) search.focus();
    });
    listen(menu, 'keydown', event => {
      if (event.key === 'Escape') { menu.open = false; menu.querySelector('summary').focus(); }
    });
    listen(document, 'click', event => { if (!menu.contains(event.target)) menu.open = false; });
    refresh.dispose = () => listeners.forEach(remove => remove());
    refresh(); return refresh;
  };
})();
