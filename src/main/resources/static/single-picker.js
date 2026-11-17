'use strict';

// A searchable single selection. Native popovers keep the menu above scrolling dialogs.
window.setupSinglePicker = function(select) {
  const trigger = document.createElement('button');
  trigger.type = 'button'; trigger.className = 'single-picker-trigger';
  trigger.setAttribute('aria-haspopup', 'listbox'); trigger.setAttribute('aria-expanded', 'false');
  trigger.setAttribute('aria-label', select.getAttribute('aria-label') || 'Selecionar opção');
  const current = document.createElement('span'); current.className = 'single-picker-current';
  const chevron = document.createElement('span'); chevron.textContent = '⌄'; chevron.setAttribute('aria-hidden', 'true');
  trigger.append(current, chevron);
  const panel = document.createElement('div'); panel.id = select.id + '-popover';
  panel.className = 'single-picker-popover'; panel.popover = 'auto';
  trigger.setAttribute('aria-controls', panel.id);
  const search = document.createElement('input'); search.type = 'search'; search.autocomplete = 'off';
  search.placeholder = 'Buscar ícone…'; search.setAttribute('aria-label', 'Buscar ícone');
  const list = document.createElement('div'); list.className = 'single-picker-list';
  list.setAttribute('role', 'listbox'); list.setAttribute('aria-label', trigger.getAttribute('aria-label'));
  const empty = document.createElement('p'); empty.textContent = 'Nenhum ícone encontrado.';
  empty.setAttribute('role', 'status'); empty.hidden = true;
  panel.append(search, list, empty);
  select.after(trigger, panel); select.hidden = true;
  const normalize = value => value.normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase('pt-BR');
  function thumbnail(option) {
    if (option.dataset.image) {
      const img = document.createElement('img'); img.src = option.dataset.image; img.alt = '';
      img.className = 'single-picker-thumbnail'; return img;
    }
    const circle = document.createElement('span'); circle.className = 'single-picker-circle';
    circle.setAttribute('aria-hidden', 'true'); return circle;
  }
  const entries = [...select.options].map(option => {
    const button = document.createElement('button'); button.type = 'button';
    button.className = 'single-picker-option'; button.setAttribute('role', 'option'); button.tabIndex = -1;
    const copy = document.createElement('span'); copy.className = 'single-picker-copy';
    const name = document.createElement('strong'); name.textContent = option.textContent;
    const description = document.createElement('small'); description.textContent = option.dataset.description || '';
    const check = document.createElement('span'); check.className = 'single-picker-check';
    check.textContent = '✓'; check.setAttribute('aria-hidden', 'true');
    copy.append(name, description); button.append(thumbnail(option), copy, check); list.append(button);
    button.onclick = () => {
      if (option.disabled || option.hidden) return;
      select.value = option.value; select.dispatchEvent(new Event('change', {bubbles: true}));
      panel.hidePopover(); trigger.focus();
    };
    return {option, button};
  });
  function sync() {
    const option = [...select.options].find(option => option.selected);
    current.replaceChildren();
    if (option) {
      const name = document.createElement('span'); name.textContent = option.textContent;
      current.append(thumbnail(option), name);
    }
    for (const entry of entries) {
      entry.button.setAttribute('aria-selected', String(entry.option.selected));
      entry.button.disabled = entry.option.disabled;
    }
    filter();
  }
  function filter() {
    const query = normalize(search.value.trim());
    for (const {option, button} of entries) button.hidden = option.disabled || option.hidden || !normalize(option.textContent).includes(query);
    empty.hidden = entries.some(entry => !entry.button.hidden);
  }
  function position() {
    if (!panel.matches(':popover-open')) return;
    const box = trigger.getBoundingClientRect(), margin = 12;
    if (box.bottom <= 0 || box.top >= window.innerHeight) { panel.hidePopover(); return; }
    const below = window.innerHeight - box.bottom - margin, above = box.top - margin;
    const height = Math.min(320, Math.max(below, above));
    const width = Math.min(box.width, window.innerWidth - margin * 2);
    panel.style.width = width + 'px'; panel.style.maxHeight = Math.max(100, height) + 'px';
    panel.style.left = Math.max(margin, Math.min(box.left, window.innerWidth - width - margin)) + 'px';
    const top = below >= Math.min(260, above) ? box.bottom + 4 : box.top - panel.offsetHeight - 4;
    panel.style.top = Math.max(margin, Math.min(top, window.innerHeight - panel.offsetHeight - margin)) + 'px';
  }
  function open() {
    search.value = ''; filter(); panel.showPopover(); position(); search.focus();
  }
  trigger.onclick = () => panel.matches(':popover-open') ? panel.hidePopover() : open();
  trigger.onkeydown = event => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {event.preventDefault(); open();}
  };
  search.oninput = filter;
  panel.onkeydown = event => {
    if (event.key === 'Escape') {
      event.preventDefault(); event.stopPropagation(); panel.hidePopover(); trigger.focus(); return;
    }
    const visible = entries.filter(entry => !entry.button.hidden).map(entry => entry.button);
    const index = visible.indexOf(document.activeElement);
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const target = index < 0 ? (event.key === 'ArrowDown' ? 0 : visible.length - 1)
        : (index + (event.key === 'ArrowDown' ? 1 : -1) + visible.length) % visible.length;
      visible[target]?.focus();
    } else if (event.key === 'Home' || event.key === 'End') {
      if (document.activeElement === search) return;
      event.preventDefault(); visible[event.key === 'Home' ? 0 : visible.length - 1]?.focus();
    } else if (event.key === 'Enter' && document.activeElement === search) {
      event.preventDefault(); visible[0]?.click();
    }
  };
  panel.addEventListener('toggle', event => trigger.setAttribute('aria-expanded', String(event.newState === 'open')));
  select.addEventListener('change', sync);
  window.addEventListener('resize', position);
  document.addEventListener('scroll', event => {if (!panel.contains(event.target)) position();}, true);
  select.closest('dialog')?.addEventListener('close', () => {if (panel.matches(':popover-open')) panel.hidePopover();});
  sync();
  return {sync, close: () => {if (panel.matches(':popover-open')) panel.hidePopover();}};
};
