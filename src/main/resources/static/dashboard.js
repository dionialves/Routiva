document.addEventListener('DOMContentLoaded', () => {
  const dashboard = document.getElementById('dashboard');
  const filters = document.getElementById('filters');
  const intervalSelect = document.getElementById('refresh-interval');
  const refreshButton = document.getElementById('refresh-now');
  const connection = document.getElementById('connection');
  const allowedIntervals = [5, 10, 15, 60];
  const storageKey = 'routiva.refreshSeconds';
  let seconds = 5;
  let timer;
  let busy = false;

  try {
    const saved = Number(localStorage.getItem(storageKey));
    if (allowedIntervals.includes(saved)) seconds = saved;
  } catch (_) {
    /* The controls also work when browser storage is unavailable. */
  }
  intervalSelect.value = String(seconds);
  htmx.config.historyRestoreAsHxRequest = false;

  function schedule() {
    clearTimeout(timer);
    if (!busy) timer = setTimeout(refresh, seconds * 1000);
  }

  function refresh() {
    if (!busy) htmx.trigger(dashboard, 'refresh');
  }

  intervalSelect.addEventListener('change', () => {
    const selected = Number(intervalSelect.value);
    seconds = allowedIntervals.includes(selected) ? selected : 5;
    intervalSelect.value = String(seconds);
    try {
      localStorage.setItem(storageKey, String(seconds));
    } catch (_) {}
    schedule();
  });
  refreshButton.addEventListener('click', refresh);

  filters.addEventListener('input', () => {
    document.getElementById('page-number').value = '1';
  });
  filters.addEventListener('change', () => {
    document.getElementById('page-number').value = '1';
  });
  filters.addEventListener('submit', (event) => {
    if (!event.detail || !event.detail.keepPage) document.getElementById('page-number').value = '1';
  });

  document.body.addEventListener('click', (event) => {
    const link = event.target.closest('.sort-link, .page-link');
    if (
      !link ||
      event.ctrlKey ||
      event.metaKey ||
      event.shiftKey ||
      event.altKey ||
      event.button > 0
    )
      return;
    event.preventDefault();
    if (link.classList.contains('page-link')) {
      document.getElementById('page-number').value = link.dataset.page;
      htmx.trigger(filters, 'submit', { keepPage: true });
      return;
    }
    document.getElementById('sort-column').value = link.dataset.sort;
    document.getElementById('sort-direction').value = link.dataset.direction;
    htmx.trigger(filters, 'submit');
  });

  function isDashboardRequest(event) {
    return event.detail.elt === dashboard || event.detail.elt === filters;
  }

  document.body.addEventListener('htmx:beforeRequest', (event) => {
    if (!isDashboardRequest(event)) return;
    busy = true;
    clearTimeout(timer);
    refreshButton.disabled = true;
    refreshButton.textContent = 'Atualizando…';
    dashboard.setAttribute('aria-busy', 'true');
  });

  document.body.addEventListener('htmx:afterRequest', (event) => {
    if (!isDashboardRequest(event)) return;
    busy = false;
    refreshButton.disabled = false;
    refreshButton.textContent = 'Atualizar agora';
    dashboard.setAttribute('aria-busy', 'false');
    connection.textContent = event.detail.successful
      ? ''
      : 'Falha ao atualizar o painel. Tentando novamente…';
    schedule();
  });

  document.body.addEventListener('htmx:sendError', (event) => {
    if (isDashboardRequest(event))
      connection.textContent = 'Conexão com o painel interrompida. Tentando novamente…';
  });
  schedule();
});
