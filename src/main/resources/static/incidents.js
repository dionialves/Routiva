/* One batched refresh for the current page; durations never query Zabbix. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const form = $('incident-filters');
  const group = $('incident-group');
  const host = $('incident-host');
  const initial = new URLSearchParams(location.search);
  let page = Math.max(1, Number(initial.get('page')) || 1);
  const size = 50;
  let sort = initial.get('sort') === 'duration' ? 'duration' : 'date';
  let direction = initial.get('direction') === 'asc' ? 'asc' : 'desc';
  const storageKey = 'routiva.incidents.filters';
  const severityOptions = [...document.querySelectorAll('[data-incident-severity]')];
  const filterKeys = ['groupId', 'hostId', 'severity'];
  function severityValue() {
    const selected = severityOptions.filter(option => option.checked).map(option => option.value);
    return selected.length === severityOptions.length ? '' : selected.length ? selected.join(',') : 'none';
  }
  function updateSeveritySummary() {
    const selected = severityOptions.filter(option => option.checked);
    $('incident-severity-summary').textContent = selected.length === severityOptions.length
      ? 'Todas as severidades' : !selected.length ? 'Nenhuma severidade'
        : selected.length === 1 ? selected[0].nextElementSibling.textContent : `${selected.length} severidades selecionadas`;
  }
  function filters() {
    const params = new URLSearchParams(new FormData(form));
    params.delete('q');
    params.set('groupId', [...group.options].filter(option => option.selected).map(option => option.value).join(','));
    params.set('hostId', [...host.options].filter(option => option.selected).map(option => option.value).join(','));
    params.set('severity', severityValue());
    return params;
  }
  function saveFilters() {
    try { localStorage.setItem(storageKey, JSON.stringify(Object.fromEntries(filters()))); } catch {}
  }
  function restoreFilters() {
    let source = initial;
    if (!filterKeys.some(key => initial.has(key))) {
      try {
        const saved = JSON.parse(localStorage.getItem(storageKey));
        if (saved && typeof saved === 'object' && !Array.isArray(saved)) {
          source = new URLSearchParams(filterKeys.map(key => [key, typeof saved[key] === 'string' ? saved[key] : '']));
        }
      } catch {}
    }
    for (const key of ['groupId', 'hostId']) {
      if (!source.has(key)) continue;
      const field = form.elements.namedItem(key);
      const value = source.get(key);
      for (const option of field.options) option.selected = value.split(',').includes(option.value);
    }
    const selected = (source.get('severity') || '').split(',');
    for (const option of severityOptions) option.checked = !source.get('severity') || selected.includes(option.value);
    filterHosts(); updateSeveritySummary();
  }
  restoreFilters();
  let applied = filters();
  let filterTimer, queuedFilters = false, filterVersion = 0;
  let busy = false, loaded = false, stopped = false, controller, refreshTimer, durationTimer;
  const dateFormat = new Intl.DateTimeFormat('pt-BR', {
    timeZone: document.querySelector('.incidents-page').dataset.timeZone,
    day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit',
    minute: '2-digit', second: '2-digit', hour12: false,
  });
  const formatDate = timestamp => timestamp > 0 ? dateFormat.format(new Date(timestamp)).replace(',', '') : '—';
  function icon(name) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'ui-icon'); svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', `/icons.svg#${name}`); svg.append(use);
    return svg;
  }
  function duration(timestamp, now = Date.now()) {
    if (!(timestamp > 0)) return '—';
    const minutes = Math.floor(Math.max(0, now - timestamp) / 60000);
    if (minutes < 1) return '< 1 min';
    if (minutes < 60) return `${minutes} min`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} h ${minutes % 60} min`;
    return `${Math.floor(hours / 24)} d ${hours % 24} h`;
  }
  function updateDurations() {
    document.querySelectorAll('[data-opened-at]').forEach(node => {
      node.textContent = duration(Number(node.dataset.openedAt));
    });
  }
  function filterHosts() {
    const groups = [...group.options].filter(option => option.selected).map(option => option.value);
    for (const option of host.options) {
      const visible = !groups.length || (option.dataset.groups || '').split(',').some(id => groups.includes(id));
      option.hidden = !visible; option.disabled = !visible;
      if (!visible) option.selected = false;
    }
  }
  function setupPicker(kind, select, allLabel) {
    const menu = $(`incident-${kind}-picker`);
    const search = $(`incident-${kind}-search`);
    const container = $(`incident-${kind}-options`);
    const summary = $(`incident-${kind}-summary`);
    const allRow = document.createElement('label');
    allRow.className = 'incident-picker-all';
    const allCheckbox = document.createElement('input'); allCheckbox.type = 'checkbox';
    const allText = document.createElement('span');
    allText.textContent = kind === 'group' ? 'Todos os grupos de hosts' : 'Todos os Hosts';
    allRow.append(allCheckbox, allText); container.append(allRow);
    allCheckbox.addEventListener('change', () => {
      for (const option of select.options) {
        if (!option.disabled) option.selected = allCheckbox.checked;
      }
      if (kind === 'group') { filterHosts(); refreshHostPicker(); }
      refresh(); scheduleFilters();
    });
    const entries = [...select.options].map(option => {
      const label = document.createElement('label');
      const checkbox = document.createElement('input'); checkbox.type = 'checkbox';
      const text = document.createElement('span'); text.textContent = option.textContent;
      label.append(checkbox, text); container.append(label);
      checkbox.addEventListener('change', () => {
        option.selected = checkbox.checked;
        if (kind === 'group') { filterHosts(); refreshHostPicker(); }
        refresh(); scheduleFilters();
      });
      return { option, checkbox, label };
    });
    function refresh() {
      const query = search.value.trim().toLocaleLowerCase('pt-BR');
      let visible = 0;
      for (const { option, checkbox, label } of entries) {
        checkbox.checked = option.selected;
        label.hidden = option.disabled || !option.textContent.toLocaleLowerCase('pt-BR').includes(query);
        if (!label.hidden) visible++;
      }
      const available = entries.filter(entry => !entry.option.disabled);
      const selected = available.filter(entry => entry.option.selected);
      allCheckbox.disabled = available.length === 0;
      allCheckbox.checked = available.length > 0 && selected.length === available.length;
      allCheckbox.indeterminate = selected.length > 0 && selected.length < available.length;
      summary.textContent = !selected.length ? allLabel : selected.length === 1
        ? selected[0].option.textContent : `${selected.length} ${kind === 'group' ? 'grupos selecionados' : 'hosts selecionados'}`;
      $(`incident-${kind}-empty`).hidden = visible !== 0;
    }
    search.addEventListener('input', refresh);
    menu.addEventListener('toggle', () => {
      if (menu.open) search.focus();
    });
    menu.addEventListener('keydown', event => {
      if (event.key === 'Escape') { menu.open = false; menu.querySelector('summary').focus(); }
    });
    document.addEventListener('click', event => { if (!menu.contains(event.target)) menu.open = false; });
    refresh(); return refresh;
  }
  function cell(tr, text) {
    const td = document.createElement('td');
    if (text != null) td.textContent = text;
    tr.append(td); return td;
  }
  function skeleton() {
    $('incident-rows').replaceChildren();
    for (let i = 0; i < 6; i++) {
      const tr = document.createElement('tr'); tr.className = 'incident-skeleton';
      tr.setAttribute('aria-hidden', 'true');
      for (let j = 0; j < 5; j++) cell(tr).append(document.createElement('span'));
      $('incident-rows').append(tr);
    }
  }
  function pagination(data) {
    const nav = $('incident-pagination'); nav.replaceChildren();
    function button(label, target, disabled = false, current = false) {
      const node = document.createElement('button'); node.type = 'button';
      node.textContent = label; node.disabled = disabled || busy;
      node.setAttribute('aria-label', label === '‹ Anterior' ? 'Página anterior' : label === 'Próxima ›' ? 'Próxima página' : `Página ${target}`);
      if (current) node.setAttribute('aria-current', 'page');
      node.addEventListener('click', () => { if (!busy) { page = target; load(); } });
      nav.append(node);
    }
    button('‹ Anterior', page - 1, page <= 1);
    const status = document.createElement('span');
    status.id = 'incident-page-status';
    status.textContent = `Página ${page} de ${data.pages}`;
    nav.append(status);
    button('Próxima ›', page + 1, page >= data.pages);
  }
  function render(data) {
    const fragment = document.createDocumentFragment();
    const severityIcons = ['help', 'info', 'alert', 'circle-alert', 'alert', 'octagon-alert'];
    for (const row of data.rows) {
      const tr = document.createElement('tr'); tr.dataset.severity = row.severity;
      cell(tr, formatDate(row.timestamp));
      const severity = document.createElement('span'); severity.className = 'incident-severity';
      severity.append(icon(severityIcons[row.severity] || 'help'), document.createTextNode(row.severityLabel));
      cell(tr).append(severity);
      const hosts = cell(tr);
      for (const item of row.hosts) {
        const wrap = document.createElement('div'); wrap.className = 'incident-host';
        const device = document.createElement('span'); device.className = 'incident-device'; device.append(icon('server'));
        const text = document.createElement('span'), name = document.createElement('strong'), ip = document.createElement('small');
        name.textContent = item.name; name.title = item.name; ip.textContent = item.ip;
        text.append(name, ip); wrap.append(device, text); hosts.append(wrap);
      }
      const description = document.createElement('span'); description.className = 'incident-description';
      description.textContent = row.name; description.title = row.name;
      cell(tr).append(description);
      const elapsed = document.createElement('span'); elapsed.className = 'incident-duration';
      const value = document.createElement('span'); value.dataset.openedAt = row.timestamp;
      value.textContent = duration(row.timestamp); elapsed.append(icon('clock-3'), value); cell(tr).append(elapsed);
      fragment.append(tr);
    }
    $('incident-rows').replaceChildren(fragment);
    page = data.page;
    $('incident-total').textContent = `${data.total} encontrados`;
    $('incident-updated').textContent = `Última atualização: ${formatDate(data.updatedAt)}`;
    $('incident-updated').dataset.state = data.warning ? 'partial' : 'success';
    $('incident-warning').textContent = data.warning; $('incident-warning').hidden = !data.warning;
    $('incident-empty').hidden = data.total !== 0;
    const filtered = ['groupId', 'hostId', 'severity'].some(key => applied.get(key)?.trim());
    $('incident-empty').querySelector('p').textContent = filtered
      ? 'Nenhum incidente corresponde aos filtros selecionados' : 'Nenhum incidente ativo encontrado';
    $('incident-range').textContent = `Exibindo ${data.total ? (page - 1) * size + 1 : 0} – ${Math.min(page * size, data.total)} de ${data.total} incidentes`;
    document.querySelectorAll('[data-incident-sort]').forEach(button => {
      button.closest('th').setAttribute('aria-sort', button.dataset.incidentSort === sort
        ? direction === 'asc' ? 'ascending' : 'descending' : 'none');
    });
    pagination(data);
    const params = new URLSearchParams(applied);
    params.set('page', page); params.set('size', size); params.set('sort', sort); params.set('direction', direction);
    history.replaceState(null, '', `/incidentes?${params}`);
  }
  async function load() {
    if (busy || stopped) return;
    clearTimeout(refreshTimer); busy = true;
    const requestFilterVersion = filterVersion;
    $('incidents-panel').setAttribute('aria-busy', 'true');
    const params = new URLSearchParams(applied);
    params.set('page', page); params.set('size', size); params.set('sort', sort); params.set('direction', direction);
    controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 20000);
    try {
      const response = await fetch(`/api/incidents?${params}`, { cache: 'no-store', signal: controller.signal });
      if (!response.ok) throw new Error('Zabbix indisponível');
      const data = await response.json();
      if (stopped || requestFilterVersion !== filterVersion) return;
      busy = false; render(data); loaded = true; $('incident-error').hidden = true;
    } catch {
      if (!stopped && requestFilterVersion === filterVersion) {
        $('incident-error').hidden = false;
        $('incident-updated').dataset.state = 'error';
        if (!loaded) {
          $('incident-rows').replaceChildren();
          $('incident-updated').textContent = 'Atualização indisponível';
          $('incident-range').textContent = 'Não foi possível carregar os incidentes';
        }
      }
    } finally {
      clearTimeout(timeout); busy = false;
      $('incidents-panel').setAttribute('aria-busy', 'false');
      if (!stopped && queuedFilters) {
        queuedFilters = false; load();
      } else if (!stopped) refreshTimer = setTimeout(load, 60000);
    }
  }
  function applyFilters() {
    clearTimeout(filterTimer);
    filterHosts(); applied = filters(); saveFilters(); page = 1;
    if (busy) queuedFilters = true;
    else { queuedFilters = false; load(); }
  }
  function scheduleFilters() {
    filterVersion++;
    queuedFilters = false;
    clearTimeout(filterTimer);
    clearTimeout(refreshTimer);
    saveFilters();
    filterTimer = setTimeout(applyFilters, 300);
  }
  const refreshHostPicker = setupPicker('host', host, 'Todos os hosts');
  setupPicker('group', group, 'Todos os grupos');
  group.addEventListener('change', () => { filterHosts(); refreshHostPicker(); });
  severityOptions.forEach(option => option.addEventListener('change', () => {
    updateSeveritySummary(); scheduleFilters();
  }));
  form.addEventListener('change', saveFilters);
  form.addEventListener('input', saveFilters);
  document.addEventListener('click', event => {
    const menu = $('incident-severity-menu');
    if (!menu.contains(event.target)) menu.open = false;
  });
  $('incident-severity-menu').addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      $('incident-severity-menu').open = false;
      $('incident-severity-menu').querySelector('summary').focus();
    }
  });
  form.addEventListener('submit', event => {
    event.preventDefault(); if (busy) return;
    applyFilters();
  });
  document.querySelectorAll('[data-incident-sort]').forEach(button => button.addEventListener('click', () => {
    if (busy) return;
    direction = sort === button.dataset.incidentSort && direction === 'desc' ? 'asc' : 'desc';
    sort = button.dataset.incidentSort; page = 1; load();
  }));
  $('incident-retry').addEventListener('click', load);
  window.addEventListener('pagehide', () => {
    stopped = true; controller?.abort(); clearTimeout(refreshTimer); clearTimeout(filterTimer); clearInterval(durationTimer);
  });
  window.addEventListener('pageshow', event => {
    if (event.persisted) { stopped = false; durationTimer = setInterval(updateDurations, 60000); load(); }
  });
  filterHosts(); skeleton(); durationTimer = setInterval(updateDurations, 60000); load();
})();
