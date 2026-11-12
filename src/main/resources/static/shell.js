document.addEventListener('DOMContentLoaded', () => {
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') document.querySelectorAll('.utility-menu[open]').forEach(menu => menu.removeAttribute('open'));
  });
  document.addEventListener('click', (event) => {
    document.querySelectorAll('.utility-menu[open]').forEach(menu => {
      if (!menu.contains(event.target)) menu.removeAttribute('open');
    });
  });
});

// Shared notification surface for every page using the application shell.
window.notify = (message, type = 'success') => {
  let stack = document.querySelector('.toast-stack');
  if (!stack) {
    stack = document.createElement('div');
    stack.className = 'toast-stack';
    stack.setAttribute('aria-label', 'Notificações');
    document.body.append(stack);
  }
  const card = document.createElement('div');
  card.className = `toast toast-${type}`;
  card.setAttribute('role', type === 'error' ? 'alert' : 'status');
  const text = document.createElement('span');
  text.textContent = message;
  const close = document.createElement('button');
  close.type = 'button'; close.textContent = '×'; close.setAttribute('aria-label', 'Fechar notificação');
  card.append(text, close); stack.append(card);
  let timer;
  const dismiss = () => { clearTimeout(timer); card.classList.add('toast-leaving'); setTimeout(() => card.remove(), 220); };
  const schedule = () => { clearTimeout(timer); timer = setTimeout(dismiss, 5000); };
  close.addEventListener('click', dismiss);
  card.addEventListener('mouseenter', () => clearTimeout(timer));
  card.addEventListener('mouseleave', schedule);
  card.addEventListener('focusin', () => clearTimeout(timer));
  card.addEventListener('focusout', schedule);
  schedule();
};
document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('[data-notification]').forEach(el => window.notify(el.textContent, el.dataset.notification));
});

// All application icons use the same outline SVG sprite.
window.AppIcon = name => {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'ui-icon');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', `/icons.svg#${name}`);
  svg.append(use);
  return svg;
};

document.addEventListener('DOMContentLoaded', () => {
  const sidebar = document.getElementById?.('sidebar');
  if (!sidebar) return;
  const collapse = document.getElementById('sidebar-collapse');
  const mobileToggle = document.getElementById('mobile-nav-toggle');
  const close = document.getElementById('sidebar-close');
  const mobile = window.matchMedia('(max-width: 760px)');
  const root = document.documentElement;
  const read = key => { try { return sessionStorage.getItem(key); } catch { return null; } };
  const write = (key, value) => { try { sessionStorage.setItem(key, value); } catch { /* Storage may be unavailable. */ } };
  const groups = [...sidebar.querySelectorAll('[data-sidebar-group]')];
  const backdrop = document.createElement('button');
  backdrop.className = 'sidebar-backdrop'; backdrop.type = 'button'; backdrop.hidden = true;
  backdrop.setAttribute('aria-label', 'Fechar menu'); backdrop.setAttribute('aria-hidden', 'true'); backdrop.tabIndex = -1;
  document.body.append(backdrop);
  let collapsed = read('traceone.sidebar.collapsed') === 'true';
  let drawerOpen = false;
  const isCollapsed = () => collapsed && !mobile.matches;
  const setGroup = (group, open, persist = true) => {
    const toggle = group.querySelector('button');
    const panel = document.getElementById(toggle.getAttribute('aria-controls'));
    toggle.setAttribute('aria-expanded', String(open));
    panel.hidden = !open;
    group.classList.toggle('expanded', open);
    if (isCollapsed() && open) panel.style.top = `${Math.min(toggle.getBoundingClientRect().top, window.innerHeight - 140)}px`;
    else panel.style.removeProperty('top');
    if (persist) write(`traceone.sidebar.${group.dataset.sidebarGroup}`, String(open));
  };
  // Resolve the active item from the URL, including the legacy /hosts alias.
  const route = window.location.pathname.replace(/\/$/, '') || '/';
  sidebar.querySelectorAll('.side-nav a').forEach(link => {
    const target = new URL(link.href, window.location.href).pathname;
    const active = route === target || (['/diagnostico/grupos', '/ap'].includes(target) && route.startsWith(target + '/')) || (target === '/configuracao/zabbix' && route === `${target}/hosts`);
    link.classList.toggle('active', active);
    if (active) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current');
  });
  groups.forEach(group => {
    const active = !!group.querySelector('[aria-current="page"]');
    group.classList.toggle('contains-active', active);
    setGroup(group, !isCollapsed() && (active || read(`traceone.sidebar.${group.dataset.sidebarGroup}`) === 'true'), false);
    group.querySelector('button').addEventListener('click', () => {
      const open = group.querySelector('button').getAttribute('aria-expanded') !== 'true';
      groups.filter(g => g !== group).forEach(g => setGroup(g, false));
      setGroup(group, open);
    });
  });
  sidebar.querySelectorAll('.side-nav a').forEach(link => {
    link.addEventListener('click', () => {
      groups.forEach(group => setGroup(group, false));
    });
  });
  const setDrawer = open => {
    drawerOpen = open && mobile.matches;
    root.classList.toggle('sidebar-drawer-open', drawerOpen);
    backdrop.hidden = !drawerOpen;
    mobileToggle.setAttribute('aria-expanded', String(drawerOpen));
    sidebar.toggleAttribute('inert', mobile.matches && !drawerOpen);
    sidebar.setAttribute('aria-hidden', String(mobile.matches && !drawerOpen));
    const workspace = document.querySelector('.app-workspace');
    workspace.toggleAttribute('inert', drawerOpen);
    if (drawerOpen) workspace.setAttribute('aria-hidden', 'true'); else workspace.removeAttribute('aria-hidden');
    if (drawerOpen) { sidebar.setAttribute('role', 'dialog'); sidebar.setAttribute('aria-modal', 'true'); close.focus(); }
    else { sidebar.removeAttribute('role'); sidebar.removeAttribute('aria-modal'); }
  };
  const apply = () => {
    root.classList.toggle('sidebar-collapsed', isCollapsed());
    collapse.setAttribute('aria-expanded', String(!isCollapsed()));
    collapse.setAttribute('aria-label', isCollapsed() ? 'Expandir menu' : 'Recolher menu');
    collapse.title = isCollapsed() ? 'Expandir menu' : 'Recolher menu';
    groups.forEach(group => setGroup(group, !isCollapsed() && (!!group.querySelector('[aria-current="page"]') || read(`traceone.sidebar.${group.dataset.sidebarGroup}`) === 'true'), false));
    setDrawer(false);
  };
  collapse.addEventListener('click', () => { collapsed = !collapsed; write('traceone.sidebar.collapsed', String(collapsed)); apply(); });
  mobileToggle.addEventListener('click', () => setDrawer(!drawerOpen));
  const dismiss = () => { setDrawer(false); mobileToggle.focus(); };
  close.addEventListener('click', dismiss); backdrop.addEventListener('click', dismiss);
  mobile.addEventListener('change', apply);
  document.addEventListener('click', event => {
    if (isCollapsed() && !sidebar.contains(event.target)) groups.forEach(g => setGroup(g, false, false));
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      if (drawerOpen) dismiss();
      else groups.filter(g => g.querySelector('button').getAttribute('aria-expanded') === 'true' && isCollapsed())
        .forEach(g => { setGroup(g, false, false); g.querySelector('button').focus(); });
    }
    if (event.key === 'Tab' && drawerOpen) {
      const items = [...sidebar.querySelectorAll('a, button, summary')].filter(el => el.getClientRects().length && !el.disabled);
      const first = items[0], last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
  });
  apply();
});

// Only classified APs link to the equipment analysis page.
window.AccessPointLinks = (() => {
  let pending;
  function attach(element, hostId) {
    element.removeAttribute('href');
    pending ||= fetch('/api/ap', { cache: 'no-store' }).then(response => {
      if (!response.ok) throw new Error('AP inventory unavailable');
      return response.json();
    }).then(rows => new Set(rows.map(row => row.hostId))).catch(() => new Set());
    pending.then(ids => { if (ids.has(hostId)) element.href = '/ap/' + encodeURIComponent(hostId); });
  }
  return { attach };
})();
