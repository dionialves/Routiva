/* Shared host actions, using the native popover for dismissal and focus handling. */
window.HostMenu = (() => {
  function winboxUrl(ip) {
    const address = String(ip || '').trim();
    // Inventory values must remain an address, never URL credentials, paths or parameters.
    if (!address || !/^[a-zA-Z0-9:.%-]+$/.test(address)) return null;
    return `winbox://${address.includes(':') ? `[${address}]` : address}`;
  }
  function attach(link, container, { ip, groupUrl, hostId } = {}) {
    const menu = document.createElement('div');
    menu.className = 'host-action-menu equipment-action-menu';
    menu.setAttribute('popover', 'auto');
    menu.setAttribute('role', 'menu');
    menu.setAttribute('aria-label', 'Ações do host');
    const actions = [];
    function section(label) {
      const group = document.createElement('div');
      group.setAttribute('role', 'group');
      group.setAttribute('aria-label', label);
      const heading = document.createElement('p');
      heading.className = 'equipment-action-label';
      heading.textContent = label;
      group.append(heading);
      menu.append(group);
      return group;
    }
    const icons = {
      browser: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a18 18 0 0 1 0 18 18 18 0 0 1 0-18Z"/>',
      window: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 8h18"/>',
      terminal: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="m7 9 3 3-3 3m6 0h4"/>',
      copy: '<rect x="8" y="7" width="12" height="15" rx="2"/><path d="M16 7V4H4v13h4"/>',
      pulse: '<path d="M2 12h5l3-9 4 18 3-9h5"/>'
    };
    function action(group, label, href, icon, handler) {
      const item = document.createElement(href ? 'a' : 'button');
      if (href) item.href = href;
      else item.type = 'button';
      item.setAttribute('role', 'menuitem');
      item.setAttribute('tabindex', '-1');
      item.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true">${icons[icon]}</svg>`;
      const text = document.createElement('span');
      text.textContent = label;
      item.append(text);
      item.actionGroup = group;
      group.append(item); actions.push(item);
      item.addEventListener('click', () => {
        menu.hidePopover();
        link.focus();
        handler?.();
      });
      return item;
    }
    const access = section('Acesso');
    const url = winboxUrl(ip);
    const browser = action(access, 'Navegador', url?.replace(/^winbox:/, 'http:'), 'browser');
    if (url) { browser.target = '_blank'; browser.rel = 'noopener noreferrer'; }
    const winbox = action(access, 'WinBox', url, 'window');
    const ssh = action(access, 'SSH', null, 'terminal');
    ssh.disabled = true;
    ssh.title = 'Acesso SSH ainda não implementado.';
    const copy = action(access, 'Copiar IP do equipamento', null, 'copy', async () => {
      try { await navigator.clipboard.writeText(String(ip).trim()); window.notify?.('IP copiado'); }
      catch { window.notify?.('Não foi possível copiar o IP. Verifique a permissão da área de transferência.', 'error'); }
    });
    for (const item of [browser, winbox, copy]) item.disabled = !url;
    if (hostId && window.AccessPointLinks) {
      const ap = section('Access Point');
      ap.hidden = true;
      const details = action(ap, 'Abrir painel', '#', 'window');
      const latency = action(ap, 'Executar avaliação de latência', '#', 'pulse');
      window.AccessPointLinks.attach(details, hostId, href => {
        latency.href = href + '#avaliacao-latencia';
        ap.hidden = false;
      });
    }
    const enabled = () => actions.filter(item => !item.disabled && !item.hidden && !item.actionGroup.hidden);
    menu.addEventListener('keydown', event => {
      if (event.key === 'Escape') { event.preventDefault(); menu.hidePopover(); link.focus(); return; }
      if (event.key === 'Tab') { menu.hidePopover(); return; }
      const items = enabled();
      const index = items.indexOf(document.activeElement);
      let next;
      if (event.key === 'ArrowDown') next = (index + 1) % items.length;
      if (event.key === 'ArrowUp') next = (index - 1 + items.length) % items.length;
      if (event.key === 'Home') next = 0;
      if (event.key === 'End') next = items.length - 1;
      if (next !== undefined) { event.preventDefault(); items[next]?.focus(); }
    });
    link.href = groupUrl || '#';
    link.setAttribute('class', `${link.getAttribute?.('class') || link.className?.baseVal || link.className || ''} host-action-link`);
    link.setAttribute('aria-haspopup', 'menu');
    link.setAttribute('aria-expanded', 'false');
    link.addEventListener('click', event => {
      if (groupUrl && (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey)) return;
      event.preventDefault();
      if (menu.matches?.(':popover-open')) { menu.hidePopover(); return; }
      const bounds = link.getBoundingClientRect();
      menu.style.left = `${Math.max(8, Math.min(bounds.left, window.innerWidth - 306))}px`;
      menu.style.top = `${bounds.bottom + 6}px`;
      menu.style.maxHeight = `${Math.max(0, window.innerHeight - 16)}px`;
      menu.showPopover();
      if (menu.getBoundingClientRect().bottom > window.innerHeight) {
        menu.style.top = `${Math.max(8, bounds.top - menu.offsetHeight - 6)}px`;
      }
      enabled()[0]?.focus();
    });
    menu.addEventListener('toggle', event => {
      link.setAttribute('aria-expanded', String(event.newState === 'open'));
    });
    container.append(menu);
    return menu;
  }
  return { attach, winboxUrl };
})();
