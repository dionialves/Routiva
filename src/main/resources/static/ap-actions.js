(() => {
  'use strict';
  const root = document.querySelector('.host-page');
  const trigger = document.getElementById('ap-settings');
  const menu = document.getElementById('ap-settings-menu');
  if (!root || !trigger || !menu) return;
  const feedback = document.getElementById('ap-action-feedback');
  let feedbackTimer;
  let refreshing = false;
  const items = () => Array.from(menu.querySelectorAll('[role=menuitem]:not(:disabled)'));
  function close(restore = false) {
    if (menu.matches(':popover-open')) menu.hidePopover();
    if (restore) trigger.focus();
  }
  function notify(message) {
    clearTimeout(feedbackTimer);
    feedback.hidden = false;
    feedback.textContent = message;
    feedbackTimer = setTimeout(() => { feedback.hidden = true; }, 5000);
  }
  function position() {
    const bounds = trigger.getBoundingClientRect();
    menu.style.left = `${Math.max(8, Math.min(bounds.right - menu.offsetWidth, innerWidth - menu.offsetWidth - 8))}px`;
    const below = innerHeight - bounds.bottom - 14;
    const above = bounds.top - 14;
    const upwards = menu.scrollHeight > below && above > below;
    menu.style.maxHeight = `${Math.max(40, upwards ? above : below)}px`;
    menu.style.top = `${upwards ? Math.max(8, bounds.top - menu.offsetHeight - 6) : bounds.bottom + 6}px`;
  }
  function states() {
    const online = root.dataset.api === 'online';
    menu.querySelectorAll('[data-remote]').forEach(item => {
      item.disabled = !online;
      item.title = online ? (item.id === 'ap-winbox' ? 'Requer WinBox e manipulador do protocolo winbox no computador.' : '') : 'Equipamento ou API indisponível.';
    });
    document.getElementById('ap-copy-ip').disabled = !root.dataset.managementIp;
  }
  function open(last = false) {
    states();
    menu.showPopover();
    position();
    (last ? items().at(-1) : items()[0])?.focus();
  }
  trigger.addEventListener('click', () => menu.matches(':popover-open') ? close() : open());
  trigger.addEventListener('keydown', event => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault(); open(event.key === 'ArrowUp');
    }
  });
  menu.addEventListener('toggle', event => trigger.setAttribute('aria-expanded', String(event.newState === 'open')));
  menu.addEventListener('keydown', event => {
    if (event.key === 'Escape') { event.preventDefault(); close(true); return; }
    if (event.key === 'Tab') { close(); return; }
    const enabled = items();
    const index = enabled.indexOf(document.activeElement);
    let next;
    if (event.key === 'ArrowDown') next = (index + 1) % enabled.length;
    if (event.key === 'ArrowUp') next = (index - 1 + enabled.length) % enabled.length;
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = enabled.length - 1;
    if (next !== undefined) { event.preventDefault(); enabled[next]?.focus(); }
  });
  menu.addEventListener('click', event => {
    if (event.target.closest('[role=menuitem]:not(:disabled)')) close(true);
  });
  addEventListener('resize', () => { if (menu.matches(':popover-open')) position(); });
  document.addEventListener('scroll', () => { if (menu.matches(':popover-open')) position(); }, true);
  new MutationObserver(states).observe(root, { attributes:true, attributeFilter:['data-api'] });
  document.getElementById('ap-winbox').addEventListener('click', () => {
    const url = window.HostMenu.winboxUrl(root.dataset.managementIp);
    if (url) window.location.href = url;
    else notify('IP inválido para abrir o WinBox.');
  });
  document.getElementById('ap-copy-ip').addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(root.dataset.managementIp); notify('IP copiado'); }
    catch { notify('Não foi possível copiar o IP. Verifique a permissão da área de transferência.'); }
  });
  for (const [id, tab] of [['ap-interfaces', 'interfaces'], ['ap-clients', 'wireless']]) {
    document.getElementById(id).addEventListener('click', () => {
      document.querySelector(`[data-tab="${tab}"]`).click();
      const panel = document.querySelector(`[data-panel="${tab}"]`);
      panel.scrollIntoView({ behavior:matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block:'start' });
      panel.setAttribute('tabindex', '-1'); panel.focus({ preventScroll:true });
    });
  }
  const refresh = document.getElementById('ap-refresh');
  refresh.addEventListener('click', async () => {
    if (refreshing) return;
    refreshing = true; refresh.disabled = true; refresh.setAttribute('aria-busy', 'true');
    refresh.querySelector('span').textContent = 'Atualizando…';
    notify('Atualizando informações…');
    try {
      await window.AccessPointActions.refresh();
      notify(root.dataset.api === 'online' ? 'Informações atualizadas' : 'Não foi possível atualizar as informações. Verifique a conexão com a API.');
    } catch { notify('Não foi possível atualizar as informações.'); }
    finally {
      refreshing = false; refresh.disabled = false; refresh.removeAttribute('aria-busy');
      refresh.querySelector('span').textContent = 'Atualizar informações';
    }
  });
  states();
})();
