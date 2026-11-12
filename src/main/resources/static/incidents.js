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
  saveFilters();
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
      for (let j = 0; j < 8; j++) cell(tr).append(document.createElement('span'));
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
      const heading = cell(header); heading.colSpan = 8;
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
          const td = cell(footer); td.colSpan = 8; pagination(period, rows.length, td); body.append(footer);
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
    updateLocation();
  }
  function hostName(item) {
    const name = document.createElement('div');
    name.className = 'incident-host';
    const groups = (item.groups || []).map(group => group.id).filter(Boolean);
    if (!groups.length) {
      name.textContent = item.name; name.title = item.name;
      return name;
    }
    const link = document.createElement('a');
    link.className = 'incident-host-link';
    link.textContent = item.name; link.title = item.name;
    const selectedGroup = groups.find(id => [...group.options].some(option => option.selected && option.value === id)) || groups[0];
    link.href = `/diagnostico/grupos/${encodeURIComponent(selectedGroup)}`;
    link.setAttribute('aria-haspopup', 'true');
    link.setAttribute('aria-expanded', 'false');
    const menu = document.createElement('div');
    menu.className = 'incident-host-menu';
    menu.setAttribute('popover', 'auto');
    const destination = document.createElement('a');
    destination.href = link.href;
    destination.textContent = 'Grupo do Host';
    menu.append(destination);
    link.addEventListener('click', event => {
      if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      const bounds = link.getBoundingClientRect();
      menu.style.left = `${Math.max(8, Math.min(bounds.left, window.innerWidth - 200))}px`;
      menu.style.top = `${bounds.bottom + 6}px`;
      menu.showPopover();
      if (menu.getBoundingClientRect().bottom > window.innerHeight) {
        menu.style.top = `${Math.max(8, bounds.top - menu.offsetHeight - 6)}px`;
      }
      destination.focus();
    });
    menu.addEventListener('toggle', event => {
      link.setAttribute('aria-expanded', String(event.newState === 'open'));
    });
    name.append(link, menu);
    return name;
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
        const name = hostName(item);
        const ip = document.createElement('div'); ip.className = 'incident-ip';
        ip.textContent = item.ip; ip.title = item.ip;
        hosts.append(name); addresses.append(ip);
      }
      const description = document.createElement('a'); description.className = 'incident-description incident-link';
      description.href = '#incident-dialog';
      description.setAttribute('aria-haspopup', 'dialog'); description.setAttribute('aria-controls', 'incident-dialog');
      description.addEventListener('click', event => { event.preventDefault(); openIncident(row, description); });
      description.textContent = row.name; description.title = row.name;
      const descriptionCell = cell(tr); descriptionCell.className = 'incident-description-cell'; descriptionCell.append(description);
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
      const observations = cell(tr); observations.className = 'incident-observations';
      if (row.messageCount > 0) {
        const messages = document.createElement('button'); messages.type = 'button';
        messages.className = 'incident-message-button'; messages.title = `${row.messageCount} ${row.messageCount === 1 ? 'mensagem' : 'mensagens'}`;
        messages.setAttribute('aria-label', `Ver ${messages.title} do incidente`); messages.setAttribute('aria-haspopup', 'dialog');
        messages.append(icon('message-square'), document.createTextNode(String(row.messageCount)));
        messages.addEventListener('click', () => openIncident(row, messages, true)); observations.append(messages);
      }
      else observations.textContent = '—';
      fragment.append(tr);
    }

  }
  const dialog = $('incident-dialog');
  const updateForm = $('incident-update-form');
  const canUpdate = document.querySelector('.incidents-page').dataset.canUpdate === 'true';
  const severityLabels = severityOptions.map(option => option.nextElementSibling.textContent);
  let activeIncident, detailsVersion = 0, saving = false, uncertain = false, returnFocus, detailsLoaded = false, hasIncidentDetails = false;
  const severityColors = ['#a6afba', '#62b6ff', '#f5db27', '#ffa45c', '#ff794f', '#ff5364'];
  function updateActions(action, item) {
    const labels = [];
    if (action & 1) labels.push('Fechamento solicitado');
    if (action & 2) labels.push('Reconhecido');
    if (action & 4) labels.push('Mensagem adicionada');
    if (action & 8) labels.push(`Severidade: ${severityLabels[item.oldSeverity] || '—'} → ${severityLabels[item.newSeverity] || '—'}`);
    if (action & 16) labels.push('Reconhecimento removido');
    if (action & 32) labels.push('Suprimido');
    if (action & 64) labels.push('Supressão removida');
    if (action & 128) labels.push('Classificado como causa');
    if (action & 256) labels.push('Classificado como sintoma');
    return labels.length ? labels : ['Atualização'];
  }
  function canCloseIncident(incident = activeIncident) {
    return Boolean(incident?.writable && incident?.manualClose && !incident?.closed && !incident?.closing);
  }
  function changedSeverity() {
    const value = Number($('incident-new-severity').value);
    return activeIncident?.writable && Number.isInteger(value) && value >= 0 && value <= 5
      && value !== activeIncident.severity ? value : null;
  }
  function updateSaveState() {
    const message = $('incident-message');
    const limit = message.maxLength > 0 ? message.maxLength : 1800;
    $('incident-message-count').textContent = `${message.value.length.toLocaleString('pt-BR')} / ${limit.toLocaleString('pt-BR')}`;
    $('incident-severity-dot').style.setProperty('--incident-level', severityColors[Number($('incident-new-severity').value)] || severityColors[0]);
    const valid = message.value.trim().length > 0 || changedSeverity() !== null
      || ($('incident-recognize').checked && !activeIncident?.acknowledged)
      || ($('incident-close').checked && canCloseIncident());
    $('incident-update-save').disabled = !detailsLoaded || !canUpdate || saving || uncertain
      || activeIncident?.closed || activeIncident?.closing || !valid || message.value.length > limit;
  }
  function renderIncidentHistory(data) {
    const history = $('incident-history'); history.replaceChildren();
    for (const item of [...data.history].sort((a, b) => b.timestamp - a.timestamp)) {
      const tr = document.createElement('tr');
      const time = document.createElement('time'); time.dateTime = new Date(item.timestamp).toISOString();
      time.textContent = formatDate(item.timestamp); cell(tr).append(time);
      cell(tr, item.user);
      const actions = cell(tr);
      for (const label of updateActions(item.action, item)) {
        const badge = document.createElement('span'); badge.className = 'incident-action-badge';
        badge.textContent = label; actions.append(badge);
      }
      const messageCell = cell(tr);
      if (item.message) {
        const message = document.createElement('button'); message.type = 'button';
        message.className = 'incident-history-message'; message.textContent = item.message;
        message.setAttribute('aria-expanded', 'false');
        message.title = 'Clique para expandir a mensagem completa';
        let expanded = false;
        message.addEventListener('click', () => {
          expanded = !expanded; message.setAttribute('aria-expanded', String(expanded));
          message.title = expanded ? 'Clique para recolher a mensagem' : 'Clique para expandir a mensagem completa';
        });
        messageCell.append(message);
      } else messageCell.textContent = '—';
      history.append(tr);
    }
    $('incident-history-count').textContent = `${data.history.length} ${data.history.length === 1 ? 'registro' : 'registros'}`;
    $('incident-history-empty').hidden = data.history.length !== 0;
  }
  async function readDetails(focusHistory = false) {
    const version = ++detailsVersion;
    const previousSeverity = hasIncidentDetails ? changedSeverity() : null;
    detailsLoaded = false;
    $('incident-update-fields').disabled = true; $('incident-update-save').disabled = true;
    $('incident-details-retry').hidden = true; $('incident-dialog-state').textContent = 'Consultando Zabbix…';
    try {
      const response = await fetch(`/api/incidents/${encodeURIComponent(activeIncident.eventId)}`, { cache: 'no-store' });
      if (!response.ok) throw new Error('Não foi possível consultar o incidente. Verifique sua sessão e a conexão com o Zabbix.');
      const data = await response.json();
      if (version !== detailsVersion || !dialog.open) return;
      activeIncident = data; detailsLoaded = true; hasIncidentDetails = true;
      $('incident-dialog-name').textContent = data.name;
      const status = data.closed ? 'Incidente encerrado' : data.closing ? 'Fechamento solicitado' : 'Incidente ativo';
      $('incident-status-badge').textContent = status;
      $('incident-status-badge').dataset.state = data.closed ? 'closed' : data.closing ? 'closing' : 'active';
      $('incident-dialog-id').textContent = `ID: ${data.eventId}`;
      $('incident-dialog-description').textContent = `${status} · ${data.closed || data.closing ? 'Consulte o histórico de atualizações.' : 'Atualize o registro, severidade ou finalize o incidente.'}`;
      $('incident-dialog-state').textContent = !canUpdate ? 'Somente administradores podem atualizar incidentes.' : '';
      renderIncidentHistory(data);
      $('incident-update-fields').disabled = !canUpdate || data.closed || data.closing;
      $('incident-new-severity').value = String(previousSeverity ?? data.severity);
      $('incident-new-severity').disabled = !data.writable;
      $('incident-recognize').disabled = data.acknowledged;
      if (data.acknowledged) $('incident-recognize').checked = true;
      $('incident-recognize-label').textContent = data.acknowledged ? 'Incidente já reconhecido' : 'Reconhecer incidente';
      const closeAllowed = canCloseIncident(data);
      $('incident-close').disabled = !closeAllowed;
      if (!closeAllowed) $('incident-close').checked = false;
      const closeReason = data.closed ? 'Este incidente já está encerrado.'
        : data.closing ? 'O fechamento deste incidente já foi solicitado ao Zabbix.'
          : !data.manualClose ? 'O Zabbix não permite o fechamento manual deste incidente.'
            : !data.writable ? 'A conta da integração não possui permissão de escrita para finalizar este incidente.' : '';
      $('incident-close-help').textContent = closeReason;
      $('incident-close').title = closeReason;

      if (uncertain) $('incident-dialog-state').textContent = 'Confira o histórico. Feche e reabra o modal antes de uma nova tentativa.';
      updateSaveState();
      if (focusHistory) $('incident-history').closest('.table-scroll').focus();
    } catch (error) {
      if (version !== detailsVersion || !dialog.open) return;
      $('incident-dialog-state').textContent = error.message; $('incident-details-retry').hidden = false;
    }
  }
  function openIncident(row, trigger, focusHistory = false) {
    returnFocus = trigger; activeIncident = row; uncertain = false; detailsLoaded = false; hasIncidentDetails = false;
    updateForm.reset(); $('incident-update-error').textContent = '';
    $('incident-history').replaceChildren(); $('incident-history-empty').hidden = true;
    $('incident-history-count').textContent = '0 registros';
    $('incident-dialog-name').textContent = row.name; $('incident-dialog-id').textContent = `ID: ${row.eventId}`;
    $('incident-status-badge').textContent = 'Consultando…'; $('incident-status-badge').dataset.state = 'loading';
    $('incident-dialog-description').textContent = 'Consultando os dados do incidente…';
    updateSaveState(); dialog.showModal(); readDetails(focusHistory);
  }
  $('incident-message').addEventListener('input', updateSaveState);
  $('incident-new-severity').addEventListener('change', updateSaveState);
  $('incident-recognize').addEventListener('change', updateSaveState);
  $('incident-close').addEventListener('change', updateSaveState);
  $('incident-details-retry').addEventListener('click', () => readDetails());
  for (const id of ['incident-dialog-cancel', 'incident-dialog-close']) {
    $(id).addEventListener('click', () => { if (!saving) dialog.close(); });
  }
  dialog.addEventListener('cancel', event => { if (saving) event.preventDefault(); });
  dialog.addEventListener('close', () => {
    detailsVersion++; detailsLoaded = false;
    if (returnFocus?.isConnected) returnFocus.focus();
    else $('incident-rows').querySelector('.incident-link')?.focus();
  });
  updateForm.addEventListener('submit', async event => {
    event.preventDefault(); updateSaveState();
    if (saving || uncertain || !canUpdate || !detailsLoaded || $('incident-update-save').disabled) return;
    const message = $('incident-message').value.trim();
    const severity = changedSeverity();
    const acknowledge = $('incident-recognize').checked && !activeIncident.acknowledged;
    const close = $('incident-close').checked && canCloseIncident();
    saving = true; $('incident-update-error').textContent = '';
    $('incident-update-fields').disabled = true; updateSaveState();
    $('incident-update-save').setAttribute('aria-busy', 'true');
    $('incident-update-save-label').textContent = 'Atualizando…';
    $('incident-dialog-cancel').disabled = true; $('incident-dialog-close').disabled = true;
    let confirmed = false;
    try {
      const token = document.querySelector('meta[name="_csrf"]')?.content;
      const header = document.querySelector('meta[name="_csrf_header"]')?.content;
      if (!token || !header) throw new Error('Atualize a página para renovar a sessão do formulário.');
      const response = await fetch(`/api/incidents/${encodeURIComponent(activeIncident.eventId)}/updates`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', [header]: token },
        body: JSON.stringify({ message, severity, acknowledge, close }),
      });
      if (!response.ok) {
        uncertain = response.status >= 500;
        throw new Error(uncertain ? 'Não foi possível confirmar a atualização. Confira o histórico antes de tentar novamente.'
          : response.status === 403 ? 'Operação não permitida ou sessão expirada. Atualize a página.'
            : 'O incidente mudou ou os dados são inválidos. Consulte novamente antes de atualizar.');
      }
      confirmed = true;
      $('incident-message').value = ''; $('incident-recognize').checked = false; $('incident-close').checked = false;
      $('incident-new-severity').value = String(activeIncident.severity);
      window.notify?.(close ? 'Fechamento solicitado ao Zabbix.' : 'Incidente atualizado.');
      if (busy) queuedFilters = true; else load();
    } catch (error) {
      // A transport error may happen after the server applied the update.
      uncertain ||= error instanceof TypeError;
      $('incident-update-error').textContent = error.message;
    } finally {
      await readDetails();
      saving = false; $('incident-dialog-cancel').disabled = false; $('incident-dialog-close').disabled = false;
      $('incident-update-save').setAttribute('aria-busy', 'false');
      $('incident-update-save-label').textContent = 'Atualizar incidente';
      updateSaveState();
      if (confirmed) dialog.close();
    }
  });
  function updateLocation() {
    // Names stay in local storage. Large selections also stay off the URL and Referer header.
    const params = new URLSearchParams();
    for (const key of ['groupId', 'hostId', 'severity']) {
      if (applied.get(key)) params.set(key, applied.get(key));
    }
    const query = params.toString();
    history.replaceState(null, '', query && query.length <= 1500 ? `/incidentes?${query}` : '/incidentes');
  }
  async function load() {
    if (busy || stopped) return;
    clearTimeout(refreshTimer); busy = true;
    const requestFilterVersion = filterVersion;
    $('incidents-panel').setAttribute('aria-busy', 'true');
    updateLocation();
    const query = Object.fromEntries(['groupId', 'hostId', 'severity'].map(key => [key, applied.get(key) || '']));
    controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 20000);
    try {
      const token = document.querySelector('meta[name="_csrf"]')?.content;
      const header = document.querySelector('meta[name="_csrf_header"]')?.content;
      if (!token || !header) throw new Error('Sessão de formulário indisponível');
      const response = await fetch('/api/incidents/grouped-data', {
        method: 'POST', headers: { 'Content-Type': 'application/json', [header]: token },
        body: JSON.stringify(query), cache: 'no-store', signal: controller.signal,
      });
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
