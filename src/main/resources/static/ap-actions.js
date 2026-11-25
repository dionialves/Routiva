(() => {
  'use strict';
  const root = document.querySelector('.host-page');
  const trigger = document.getElementById('ap-settings');
  const menu = document.getElementById('ap-settings-menu');
  if (!root || !trigger || !menu) return;
  const items = () => Array.from(menu.querySelectorAll('[role=menuitem]:not(:disabled):not([hidden])'));
  function close(restore = false) {
    if (menu.matches(':popover-open')) menu.hidePopover();
    if (restore) trigger.focus();
  }
  function position() {
    const edge = 8;
    const gap = 6;
    const bounds = trigger.getBoundingClientRect();
    const viewport = window.visualViewport;
    const left = viewport?.offsetLeft || 0;
    const top = viewport?.offsetTop || 0;
    const width = viewport?.width || innerWidth;
    const height = viewport?.height || innerHeight;
    // Measure without the previous opening's height constraint.
    menu.style.maxHeight = `${Math.max(0, height - edge * 2)}px`;
    const naturalHeight = menu.scrollHeight + menu.offsetHeight - menu.clientHeight;
    const below = Math.max(0, top + height - bounds.bottom - gap - edge);
    const above = Math.max(0, bounds.top - gap - top - edge);
    const upwards = naturalHeight > below && above > below;
    menu.style.maxHeight = `${upwards ? above : below}px`;
    menu.style.left = `${Math.max(left + edge, Math.min(bounds.right - menu.offsetWidth, left + width - menu.offsetWidth - edge))}px`;
    menu.style.top = `${upwards ? bounds.top - menu.offsetHeight - gap : bounds.bottom + gap}px`;
  }

  function states() {
    const online = root.dataset.api === 'online';
    menu.querySelectorAll('[data-remote]').forEach(item => {
      item.disabled = !online;
      item.title = online ? (item.id === 'ap-winbox' ? 'Requer WinBox e manipulador do protocolo winbox no computador.' : '') : 'Equipamento ou API indisponível.';
    });
    document.getElementById('ap-webfig').disabled = !window.HostMenu.winboxUrl(root.dataset.managementIp);
    document.getElementById('ap-copy-ip').disabled = !root.dataset.managementIp;
  }
  function open(last = false) {
    states();
    menu.showPopover();
    position();
    (last ? items().at(-1) : items()[0])?.focus({ preventScroll:true });
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
    const item = event.target.closest('[role=menuitem]:not(:disabled):not([hidden])');
    if (!item) return;
    close(true);
  });
  const reposition = () => { if (menu.matches(':popover-open')) position(); };
  addEventListener('resize', reposition);
  window.visualViewport?.addEventListener('resize', reposition);
  window.visualViewport?.addEventListener('scroll', reposition);
  document.addEventListener('scroll', () => { if (menu.matches(':popover-open')) position(); }, true);
  new MutationObserver(states).observe(root, { attributes:true, attributeFilter:['data-api'] });
  document.getElementById('ap-winbox').addEventListener('click', () => {
    const url = window.HostMenu.winboxUrl(root.dataset.managementIp);
    if (url) window.location.href = url;
    else window.notify('IP inválido para abrir o WinBox.', 'error');
  });
  for (const protocol of ['ssh', 'telnet']) {
    document.getElementById(`ap-${protocol}`).addEventListener('click', () =>
      window.HostMenu.openTerminal(protocol, root.dataset.hostId));
  }
  document.getElementById('ap-webfig').addEventListener('click', () => {
    const url = window.HostMenu.winboxUrl(root.dataset.managementIp);
    if (url) window.open(url.replace(/^winbox:/, 'http:'), '_blank', 'noopener,noreferrer');
    else window.notify('IP inválido para abrir no navegador.', 'error');
  });
  document.getElementById('ap-copy-ip').addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(root.dataset.managementIp); window.notify('IP copiado'); }
    catch { window.notify('Não foi possível copiar o IP. Verifique a permissão da área de transferência.', 'error'); }
  });
  states();
})();
