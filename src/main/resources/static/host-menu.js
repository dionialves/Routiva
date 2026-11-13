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
    menu.className = 'host-action-menu';
    menu.setAttribute('popover', 'auto');
    const actions = [];
    function action(label, href) {
      const item = document.createElement('a');
      item.textContent = label; item.href = href;
      menu.append(item); actions.push(item);
      item.addEventListener('click', () => menu.hidePopover());
      return item;
    }
    if (groupUrl) action('Grupo do Host', groupUrl);
    if (hostId && window.AccessPointLinks) {
      const details = action('Análise do equipamento', '#');
      details.hidden = true;
      window.AccessPointLinks.attach(details, hostId);
      // AccessPointLinks resolves the classification asynchronously.
      const observer = new MutationObserver(() => {
        if (details.getAttribute('href')) { details.hidden = false; observer.disconnect(); }
      });
      observer.observe(details, { attributes: true, attributeFilter: ['href'] });
    }
    const url = winboxUrl(ip);
    if (url) action('Abrir WinBox', url);
    if (!actions.length) return;
    link.href = groupUrl || '#';
    link.className += ' host-action-link';
    link.setAttribute('aria-haspopup', 'true');
    link.setAttribute('aria-expanded', 'false');
    link.addEventListener('click', event => {
      if (groupUrl && (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey)) return;
      event.preventDefault();
      const bounds = link.getBoundingClientRect();
      menu.style.left = `${Math.max(8, Math.min(bounds.left, window.innerWidth - 220))}px`;
      menu.style.top = `${bounds.bottom + 6}px`;
      menu.showPopover();
      if (menu.getBoundingClientRect().bottom > window.innerHeight) {
        menu.style.top = `${Math.max(8, bounds.top - menu.offsetHeight - 6)}px`;
      }
      actions.find(item => !item.hidden)?.focus();
    });
    menu.addEventListener('toggle', event => {
      link.setAttribute('aria-expanded', String(event.newState === 'open'));
    });
    container.append(menu);
  }
  return { attach, winboxUrl };
})();
