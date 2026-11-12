/* One filtered snapshot per refresh; period pagination and durations stay local. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const form = $('incident-filters');
  const group = $('incident-group');
  const host = $('incident-host');
  const incidentName = $('incident-name');
  let selectedNames = new Set(), refreshNamePicker;
  const initial = new URLSearchParams(location.search);
  let snapshot, calendarDay;
  const periods = [
    { id: 'today', label: 'Hoje', expanded: true, page: 1 },
    { id: 'yesterday', label: 'Ontem', expanded: true, page: 1 },
    { id: 'week', label: 'Últimos 7 dias', expanded: true, page: 1 },
    { id: 'older', label: 'Anteriores', expanded: false, page: 1 },
  ];
  const size = 50;
  const storageKey = 'routiva.incidents.filters';
  const severityOptions = [...document.querySelectorAll('[data-incident-severity]')];
  const filterKeys = ['groupId', 'hostId', 'severity', 'incidentNames'];
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
    if (selectedNames.size) params.set('incidentNames', JSON.stringify([...selectedNames]));
    else params.delete('incidentNames');
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
    try {
      const names = JSON.parse(source.get('incidentNames') || '[]');
      if (Array.isArray(names)) selectedNames = new Set(names.filter(name => typeof name === 'string'));
    } catch {}
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
    if (snapshot && dayNumber(Date.now()) !== calendarDay) render(snapshot);
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
      for (let j = 0; j < 7; j++) cell(tr).append(document.createElement('span'));
      $('incident-rows').append(tr);
    }
  }
  const calendarFormat = new Intl.DateTimeFormat('en-CA', {
    timeZone: document.querySelector('.incidents-page').dataset.timeZone,
    year: 'numeric', month: '2-digit', day: '2-digit',
  });
  function dayNumber(timestamp) {
    const parts = Object.fromEntries(calendarFormat.formatToParts(new Date(timestamp))
      .map(part => [part.type, part.value]));
    return Date.UTC(Number(parts.year), Number(parts.month) - 1, Number(parts.day)) / 86400000;
  }
  function calendarLabel(day, year = true) {
    return new Intl.DateTimeFormat('pt-BR', {
      timeZone: 'UTC', day: '2-digit', month: '2-digit', ...(year ? { year: 'numeric' } : {}),
    }).format(new Date(day * 86400000));
  }
  function pagination(period, count, container) {
    const pages = Math.max(1, Math.ceil(count / size));
    if (pages === 1) return;
    const nav = document.createElement('nav'); nav.className = 'pagination';
    nav.setAttribute('aria-label', `Paginação: ${period.label}`);
    function button(label, target, disabled) {
      const node = document.createElement('button'); node.type = 'button';
      node.className = 'system-button';
      node.append(icon(target < period.page ? 'chevron-left' : 'chevron-right'), document.createTextNode(label.replace(/[‹›]/g, '').trim())); node.disabled = disabled;
      node.addEventListener('click', () => { period.page = target; render(snapshot); });
      nav.append(node);
    }
    button('‹ Anterior', period.page - 1, period.page <= 1);
    const status = document.createElement('span');
    status.textContent = `Página ${period.page} de ${pages} · ${count} incidentes`;
    nav.append(status);
    button('Próxima ›', period.page + 1, period.page >= pages);
    container.append(nav);
  }
  function updateNameOptions(data) {
    const names = [...new Set([...selectedNames, ...data.rows.map(row => row.name)])]
      .sort((a, b) => a.localeCompare(b, 'pt-BR'));
    refreshNamePicker?.dispose();
    incidentName.replaceChildren(...names.map(name => {
      const option = document.createElement('option');
      option.value = name; option.textContent = name; option.selected = selectedNames.has(name);
      return option;
    }));
    $('incident-name-options').replaceChildren();
    refreshNamePicker = window.setupMultiPicker('name', incidentName, 'Todos os incidentes', () => {
      selectedNames = new Set([...incidentName.options].filter(option => option.selected).map(option => option.value));
      applied = filters(); saveFilters(); periods.forEach(period => { period.page = 1; });
      if (snapshot) render(snapshot);
    });
  }
  function render(data) {
    snapshot = data;
    calendarDay = dayNumber(Date.now());
    const fragment = document.createDocumentFragment();
    const grouped = periods.map(() => []);
    const matchingRows = selectedNames.size ? data.rows.filter(row => selectedNames.has(row.name)) : data.rows;
    const total = selectedNames.size ? matchingRows.length : data.total;
    for (const row of matchingRows) {
      const age = calendarDay - dayNumber(row.timestamp);
      grouped[age <= 0 ? 0 : age === 1 ? 1 : age <= 7 ? 2 : 3].push(row);
    }
    const labels = [calendarLabel(calendarDay), calendarLabel(calendarDay - 1),
      `${calendarLabel(calendarDay - 7, false)} a ${calendarLabel(calendarDay - 2, false)}`,
      `Antes de ${calendarLabel(calendarDay - 7)}`];
    let visible = 0;
    periods.forEach((period, index) => {
      const rows = grouped[index].sort((a, b) => b.timestamp - a.timestamp || a.eventId.localeCompare(b.eventId));
      if (!rows.length) return;
      period.page = Math.min(period.page, Math.ceil(rows.length / size));
      const header = document.createElement('tr'); header.className = 'incident-period';
      header.dataset.period = period.id;
      const heading = cell(header); heading.colSpan = 7;
      const toggle = document.createElement('button'); toggle.type = 'button';
      toggle.setAttribute('aria-expanded', String(period.expanded));

      const title = document.createElement('strong'); title.textContent = period.label;
      const dates = document.createElement('span'); dates.className = 'incident-period-dates'; dates.textContent = labels[index];
      const count = document.createElement('span'); count.className = 'incident-period-count';
      count.textContent = `${rows.length} ${rows.length === 1 ? 'incidente' : 'incidentes'}`;
      toggle.append(title, dates, count, icon(period.expanded ? 'chevron-down' : 'chevron-right'));
      toggle.addEventListener('click', () => {
        period.expanded = !period.expanded;
        render(snapshot);
        document.getElementById(`incident-toggle-${period.id}`)?.focus();
      });
      toggle.id = `incident-toggle-${period.id}`;
      heading.append(toggle); fragment.append(header);
      const body = document.createDocumentFragment();
      if (period.expanded) {
        const start = (period.page - 1) * size;
        const selected = rows.slice(start, start + size); visible += selected.length;
        renderRows(selected, body, index);
        if (rows.length > size) {
          const footer = document.createElement('tr'); footer.className = 'incident-period-pagination';
          const td = cell(footer); td.colSpan = 7; pagination(period, rows.length, td); body.append(footer);
        }
      }
      fragment.append(body);
    });
    $('incident-rows').replaceChildren(fragment);

    $('incident-total').textContent = `${total} encontrados`;
    $('incident-updated').textContent = `Última atualização: ${formatDate(data.updatedAt)}`;
    $('incident-updated').dataset.state = data.warning ? 'partial' : 'success';
    $('incident-warning').textContent = data.warning; $('incident-warning').hidden = !data.warning;
    $('incident-empty').hidden = total !== 0;
    const filtered = filterKeys.some(key => applied.get(key)?.trim());
    $('incident-empty').querySelector('p').textContent = filtered
      ? 'Nenhum incidente corresponde aos filtros selecionados' : 'Nenhum incidente ativo encontrado';
    $('incident-range').textContent = `Exibindo ${visible} de ${total} incidentes`;
    const params = new URLSearchParams(applied);
    history.replaceState(null, '', `/incidentes?${params}`);
  }
  function renderRows(rows, fragment, index) {
    const severityIcons = ['help', 'info', 'alert', 'circle-alert', 'alert', 'octagon-alert'];
    for (const row of rows) {
      const tr = document.createElement('tr'); tr.dataset.severity = row.severity;
      const date = cell(tr, formatDate(row.timestamp));
      date.title = formatDate(row.timestamp);
      const parts = dateFormat.formatToParts(new Date(row.timestamp));
      const values = Object.fromEntries(parts.map(part => [part.type, part.value]));
      date.textContent = index < 2 ? `${values.hour}:${values.minute}:${values.second}`
        : `${values.day}/${values.month}${index === 3 ? '/' + values.year : ''} ${values.hour}:${values.minute}`;
      const severity = document.createElement('span'); severity.className = 'incident-severity';
      severity.append(icon(severityIcons[row.severity] || 'help'), document.createTextNode(row.severityLabel));
      cell(tr).append(severity);
      const hosts = cell(tr), addresses = cell(tr);
      for (const item of row.hosts) {
        const name = document.createElement('div'); name.className = 'incident-host';
        name.textContent = item.name; name.title = item.name;
        const ip = document.createElement('div'); ip.className = 'incident-ip';
        ip.textContent = item.ip; ip.title = item.ip;
        hosts.append(name); addresses.append(ip);
      }
      const description = document.createElement('span'); description.className = 'incident-description';
      description.textContent = row.name; description.title = row.name;
      cell(tr).append(description);
      const elapsed = document.createElement('span'); elapsed.className = 'incident-duration';
      const value = document.createElement('span'); value.dataset.openedAt = row.timestamp;
      value.textContent = duration(row.timestamp); elapsed.append(icon('clock-3'), value); cell(tr).append(elapsed);
      const recognition = cell(tr, row.acknowledged ? '' : 'Não');
      if (row.acknowledged) {
        const badge = document.createElement('span');
        badge.className = 'incident-acknowledged';
        badge.textContent = 'Sim';
        recognition.append(badge);
      }
      recognition.title = row.acknowledged ? 'Incidente reconhecido' : 'Incidente não reconhecido';
      fragment.append(tr);
    }

  }
  async function load() {
    if (busy || stopped) return;
    clearTimeout(refreshTimer); busy = true;
    const requestFilterVersion = filterVersion;
    $('incidents-panel').setAttribute('aria-busy', 'true');
    const params = new URLSearchParams(applied);
    controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 20000);
    try {
      const response = await fetch(`/api/incidents/grouped-data?${params}`, { cache: 'no-store', signal: controller.signal });
      if (!response.ok) throw new Error('Zabbix indisponível');
      const data = await response.json();
      if (stopped || requestFilterVersion !== filterVersion) return;
      busy = false; updateNameOptions(data); render(data); loaded = true; $('incident-error').hidden = true;
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
    filterHosts(); applied = filters(); saveFilters(); periods.forEach(period => { period.page = 1; });
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
  refreshNamePicker = window.setupMultiPicker('name', incidentName, 'Todos os incidentes');
  const refreshHostPicker = window.setupMultiPicker('host', host, 'Todos os hosts', scheduleFilters);
  window.setupMultiPicker('group', group, 'Todos os grupos', () => { filterHosts(); refreshHostPicker(); scheduleFilters(); });
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
  $('incident-retry').addEventListener('click', load);
  window.addEventListener('pagehide', () => {
    stopped = true; controller?.abort(); clearTimeout(refreshTimer); clearTimeout(filterTimer); clearInterval(durationTimer);
  });
  window.addEventListener('pageshow', event => {
    if (event.persisted) { stopped = false; durationTimer = setInterval(updateDurations, 60000); load(); }
  });
  filterHosts(); skeleton(); durationTimer = setInterval(updateDurations, 60000); load();
})();
