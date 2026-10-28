document.addEventListener('DOMContentLoaded', () => {
  const toggle = document.querySelector('.collapse-menu');
  function setCollapsed(collapsed) {
    document.body.classList.toggle('sidebar-collapsed', collapsed);
    toggle.setAttribute('aria-expanded', String(!collapsed));
    toggle.setAttribute('aria-label', collapsed ? 'Expandir menu' : 'Recolher menu');
    toggle.querySelector('span').textContent = collapsed ? 'Expandir menu' : 'Recolher menu';
  }
  if (toggle) {
    try { setCollapsed(localStorage.getItem('traceone.sidebarCollapsed') === 'true'); } catch (_) {}
    toggle.addEventListener('click', () => {
      const collapsed = !document.body.classList.contains('sidebar-collapsed');
      setCollapsed(collapsed);
      try { localStorage.setItem('traceone.sidebarCollapsed', String(collapsed)); } catch (_) {}
    });
  }
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') document.querySelectorAll('.utility-menu[open]').forEach(menu => menu.removeAttribute('open'));
  });
  document.addEventListener('click', (event) => {
    document.querySelectorAll('.utility-menu[open]').forEach(menu => {
      if (!menu.contains(event.target)) menu.removeAttribute('open');
    });
  });
});
