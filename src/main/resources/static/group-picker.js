/* Search the already loaded inventory; only a confirmed selection changes the dashboard. */
(() => {
  const select = document.getElementById('group');
  if (!select) return;
  const wrapper = select.parentElement;
  wrapper.classList.add('group-picker');
  const trigger = document.createElement('button');
  trigger.type = 'button'; trigger.className = 'group-picker-trigger';
  trigger.setAttribute('aria-haspopup', 'listbox'); trigger.setAttribute('aria-expanded', 'false');
  trigger.setAttribute('aria-labelledby', 'group-label group-selection');
  const selection = document.createElement('span'); selection.id = 'group-selection';
  const arrow = document.createElement('span'); arrow.textContent = '⌄'; arrow.setAttribute('aria-hidden', 'true');
  trigger.append(selection, arrow);
  const panel = document.createElement('div'); panel.className = 'group-picker-panel'; panel.hidden = true;
  const input = document.createElement('input'); input.type = 'search'; input.placeholder = 'Pesquisar grupo…'; input.autocomplete = 'off';
  input.setAttribute('role', 'combobox'); input.setAttribute('aria-label', 'Pesquisar grupo de hosts');
  input.setAttribute('aria-autocomplete', 'list'); input.setAttribute('aria-controls', 'group-results'); input.setAttribute('aria-expanded', 'false');
  const list = document.createElement('div'); list.id = 'group-results'; list.className = 'group-picker-results'; list.setAttribute('role', 'listbox'); list.setAttribute('aria-label', 'Grupos de hosts');
  const empty = document.createElement('p'); empty.textContent = 'Nenhum grupo encontrado.'; empty.setAttribute('role', 'status'); empty.hidden = true;
  panel.append(input, list, empty); wrapper.append(trigger, panel); select.hidden = true;
  const dialog = select.closest?.('dialog');
  if (dialog) {
    panel.popover = 'manual';
    window.addEventListener('resize', positionPanel);
    document.addEventListener('scroll', event => {
      if (!panel.contains(event.target)) positionPanel();
    }, true);
    dialog.addEventListener('close', () => close());
  }
  function positionPanel() {
    if (!dialog || panel.hidden) return;
    const field = trigger.getBoundingClientRect();
    const gap = 6;
    const below = window.innerHeight - field.bottom - gap;
    const above = field.top - gap;
    panel.style.width = field.width + 'px';
    const upwards = panel.scrollHeight > below && above > below;
    panel.style.left = field.left + 'px';
    panel.style.top = upwards ? 'auto' : field.bottom + gap + 'px';
    panel.style.bottom = upwards ? window.innerHeight - field.top + gap + 'px' : 'auto';
    panel.style.maxHeight = Math.max(0, (upwards ? above : below) - gap) + 'px';
  }
  let matches = [], active = -1, signature = '';
  const normalize = text => text.normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase('pt-BR');
  function close(returnFocus = false) {
    if (dialog && panel.matches(':popover-open')) panel.hidePopover();
    panel.hidden = true; trigger.setAttribute('aria-expanded', 'false'); input.setAttribute('aria-expanded', 'false'); input.removeAttribute('aria-activedescendant');
    if (returnFocus) trigger.focus();
  }
  function highlight(index) {
    active = index;
    Array.from(list.children).forEach((el, i) => el.classList.toggle('is-active', i === index));
    const option = list.children[index];
    if (option) { input.setAttribute('aria-activedescendant', option.id); option.scrollIntoView({block: 'nearest'}); }
    else input.removeAttribute('aria-activedescendant');
  }
  function choose(index) {
    if (!matches[index]) return;
    const previous = select.value;
    select.value = matches[index].value; sync(); close(true);
    if (select.value !== previous) select.dispatchEvent(new Event('change', {bubbles: true}));
  }
  function render() {
    const query = normalize(input.value.trim());
    matches = Array.from(select.options).filter(option => normalize(option.textContent).includes(query));
    list.replaceChildren();
    matches.forEach((option, index) => {
      const result = document.createElement('button'); result.type = 'button'; result.tabIndex = -1;
      result.id = 'group-result-' + index; result.setAttribute('role', 'option'); result.setAttribute('aria-selected', String(option.value === select.value));
      result.textContent = option.textContent; result.addEventListener('click', () => choose(index)); list.append(result);
    });
    empty.hidden = matches.length !== 0; highlight(-1);
  }
  function open() {
    panel.hidden = false; trigger.setAttribute('aria-expanded', 'true'); input.setAttribute('aria-expanded', 'true'); input.value = ''; render();
    if (dialog) { panel.showPopover(); positionPanel(); }
    input.focus();
  }
  function sync() {
    selection.textContent = select.selectedOptions[0]?.textContent || 'Todos os hosts';
    const next = JSON.stringify(Array.from(select.options, option => [option.value, option.textContent]));
    if (next !== signature && !panel.hidden) render();
    signature = next;
  }
  trigger.addEventListener('click', () => panel.hidden ? open() : close());
  trigger.addEventListener('keydown', event => { if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); open(); } });
  input.addEventListener('input', render);
  input.addEventListener('keydown', event => {
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(true); }
    else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault(); highlight(Math.max(0, Math.min(matches.length - 1, active + (event.key === 'ArrowDown' ? 1 : -1))));
    } else if (event.key === 'Enter') { event.preventDefault(); choose(active < 0 ? 0 : active); }
  });
  wrapper.addEventListener('focusout', event => { if (!wrapper.contains(event.relatedTarget)) close(); });
  document.addEventListener('click', event => { if (!wrapper.contains(event.target)) close(); });
  select.addEventListener('change', sync);
  window.availabilityGroupPicker = {sync}; sync();
})();
