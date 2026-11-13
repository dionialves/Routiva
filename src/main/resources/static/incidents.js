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
  const eventId = initial.get('eventId') || '';
  let snapshot, calendarDay;
  const periods = [
    { id: 'today', label: 'Hoje', expanded: true, page: 1 },
    { id: 'yesterday', label: 'Ontem', expanded: true, page: 1 },
    { id: 'week', label: 'Últimos 7 dias', expanded: true, page: 1 },
    { id: 'older', label: 'Anteriores', expanded: !!eventId, page: 1 },
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
    if (eventId) return;
    try { localStorage.setItem(storageKey, JSON.stringify(Object.fromEntries(filters()))); } catch {}
  }
  function restoreFilters() {
    let source = initial;
    if (!eventId && !filterKeys.some(key => initial.has(key))) {
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
    updateOccurrenceTimes();
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
    hideComments();
    snapshot = data;
    calendarDay = dayNumber(Date.now());
    const fragment = document.createDocumentFragment();
    const grouped = periods.map(() => []);
    const matchingRows = data.rows.filter(row => (!eventId || row.eventId === eventId)
      && (!selectedNames.size || selectedNames.has(row.name)));
    const total = eventId || selectedNames.size ? matchingRows.length : data.total;
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
    const filtered = !!eventId || filterKeys.some(key => applied.get(key)?.trim());
    $('incident-empty').querySelector('p').textContent = filtered
      ? 'Nenhum incidente corresponde aos filtros selecionados' : 'Nenhum incidente ativo encontrado';
    $('incident-range').textContent = `Exibindo ${visible} de ${total} incidentes`;
    updateLocation();
  }
  function hostName(item) {
    const name = document.createElement('div');
    name.className = 'incident-host';
    const groups = (item.groups || []).map(group => group.id).filter(Boolean);
    const link = document.createElement('a');
    link.className = 'incident-host-link';
    link.textContent = item.name; link.title = item.name;
    const selectedGroup = groups.find(id => [...group.options].some(option => option.selected && option.value === id)) || groups[0];
    name.append(link);
    window.HostMenu.attach(link, name, {
      ip: item.ip,
      groupUrl: selectedGroup ? `/diagnostico/grupos/${encodeURIComponent(selectedGroup)}` : null
    });
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
      renderCommentIndicator(observations, row);
      fragment.append(tr);
    }

  }
  const dialog = $('incident-dialog');
  const updateForm = $('incident-update-form');
  const canUpdate = document.querySelector('.incidents-page').dataset.canUpdate === 'true';
  const severityLabels = severityOptions.map(option => option.nextElementSibling.textContent);
  let activeIncident, detailsVersion = 0, saving = false, uncertain = false, returnFocus, detailsLoaded = false, hasIncidentDetails = false;
  const severityColors = ['#a6afba', '#62b6ff', '#f5db27', '#ffa45c', '#ff794f', '#ff5364'];
  const commentsTooltip = $('incident-comments-tooltip');
  const commentsCache = new Map();
  let commentsAnchor = null, commentsOpening, commentsClosing;
  function hideComments() {
    clearTimeout(commentsOpening); clearTimeout(commentsClosing);
    commentsAnchor?.removeAttribute('aria-describedby');
    commentsAnchor = null;
    commentsTooltip.hidePopover?.(); commentsTooltip.hidden = true;
  }
  function positionComments(anchor) {
    const bounds = anchor.getBoundingClientRect();
    const tooltip = commentsTooltip.getBoundingClientRect();
    const width = document.documentElement.clientWidth, height = window.innerHeight;
    commentsTooltip.style.left = Math.max(8, Math.min(bounds.right - tooltip.width, width - tooltip.width - 8)) + 'px';
    const below = bounds.bottom + 8;
    commentsTooltip.style.top = Math.max(8, below + tooltip.height <= height - 8
      ? below : bounds.top - tooltip.height - 8) + 'px';
  }
  function renderComments(history) {
    const rows = history.filter(item => item.message?.trim()).sort((a, b) => b.timestamp - a.timestamp);
    const body = $('incident-comments-rows'); body.replaceChildren();
    for (const item of rows) {
      const tr = document.createElement('tr');
      cell(tr, formatDate(item.timestamp)); cell(tr, item.user || '—'); cell(tr, item.message);
      body.append(tr);
    }
    $('incident-comments-table').hidden = rows.length === 0;
    $('incident-comments-state').textContent = rows.length ? '' : 'Nenhum comentário registrado.';
  }
  async function showComments(row, anchor, inModal = false) {
    clearTimeout(commentsOpening); clearTimeout(commentsClosing);
    commentsAnchor = anchor;
    (inModal ? dialog : $('incident-comments-host')).append(commentsTooltip);
    anchor.setAttribute('aria-describedby', 'incident-comments-tooltip');
    commentsTooltip.hidden = false;
    $('incident-comments-table').hidden = true;
    $('incident-comments-state').textContent = 'Carregando comentários…';
    commentsTooltip.showPopover?.(); positionComments(anchor);
    try {
      let entry = commentsCache.get(row.eventId);
      if (!entry || Date.now() - entry.at > 30000) {
        const promise = fetch(`/api/incidents/${encodeURIComponent(row.eventId)}`, { cache: 'no-store' })
          .then(async response => {
            if (!response.ok) throw new Error('Não foi possível consultar os comentários. Passe o mouse novamente para tentar.');
            const data = await response.json(); return data.history || [];
          });
        entry = { at: Date.now(), promise }; commentsCache.set(row.eventId, entry);
      }
      const history = await entry.promise;
      if (commentsAnchor !== anchor) return;
      renderComments(history); positionComments(anchor);
    } catch (error) {
      commentsCache.delete(row.eventId);
      if (commentsAnchor !== anchor) return;
      $('incident-comments-table').hidden = true;
      $('incident-comments-state').textContent = error instanceof TypeError
        ? 'Não foi possível consultar os comentários. Passe o mouse novamente para tentar.' : error.message;
      positionComments(anchor);
    }
  }
  function scheduleCommentsClose() {
    clearTimeout(commentsOpening); clearTimeout(commentsClosing);
    commentsClosing = setTimeout(hideComments, 200);
  }
  function renderCommentIndicator(container, row, inModal = false) {
    if (!(row.messageCount > 0)) { container.textContent = '—'; return; }
    const button = document.createElement('button'); button.type = 'button';
    button.className = 'incident-message-button';
    const label = `${row.messageCount} ${row.messageCount === 1 ? 'mensagem' : 'mensagens'}`;
    button.setAttribute('aria-label', `Ver ${label} do incidente`);
    button.append(icon('message-square'), document.createTextNode(String(row.messageCount)));
    attachComments(row, button, inModal); container.append(button);
  }
  function attachComments(row, anchor, inModal = false) {
    anchor.addEventListener('mouseenter', () => {
      clearTimeout(commentsOpening); clearTimeout(commentsClosing);
      commentsOpening = setTimeout(() => showComments(row, anchor, inModal), 150);
    });
    anchor.addEventListener('mouseleave', scheduleCommentsClose);
    anchor.addEventListener('focus', () => showComments(row, anchor, inModal));
    anchor.addEventListener('blur', scheduleCommentsClose);
    anchor.addEventListener('click', () => showComments(row, anchor, inModal));
  }
  commentsTooltip.addEventListener('mouseenter', () => clearTimeout(commentsClosing));
  commentsTooltip.addEventListener('mouseleave', scheduleCommentsClose);
  commentsTooltip.addEventListener('focusin', () => clearTimeout(commentsClosing));
  commentsTooltip.addEventListener('focusout', scheduleCommentsClose);
  document.addEventListener('keydown', event => { if (event.key === 'Escape') hideComments(); });
  document.addEventListener('scroll', event => {
    if (commentsAnchor && !commentsTooltip.contains(event.target)) hideComments();
  }, true);
  window.addEventListener('resize', hideComments);
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
  const severityTrigger = $('incident-severity-trigger');
  const severityMenu = $('incident-severity-listbox');
  const severitySelect = $('incident-new-severity');
  let focusedSeverity = 0;
  const severityChoices = severityLabels.map((label, level) => {
    const option = document.createElement('button'); option.type = 'button';
    option.id = `incident-severity-option-${level}`;
    option.className = 'incident-severity-choice'; option.tabIndex = -1;
    option.setAttribute('role', 'option');
    option.style.setProperty('--incident-level', severityColors[level]);
    const dot = document.createElement('span'); dot.className = 'incident-severity-choice-dot';
    dot.setAttribute('aria-hidden', 'true');
    const text = document.createElement('span'); text.textContent = label;
    option.append(dot, text);
    option.addEventListener('click', () => chooseSeverity(level));
    severityMenu.append(option); return option;
  });
  severitySelect.hidden = true;
  severitySelect.setAttribute('aria-hidden', 'true'); severitySelect.tabIndex = -1;
  severityTrigger.hidden = false;
  function closeSeverityMenu(focus = false) {
    severityMenu.hidePopover?.(); severityMenu.hidden = true;
    severityTrigger.setAttribute('aria-expanded', 'false');
    if (focus && !severityTrigger.disabled) severityTrigger.focus();
  }
  function syncSeverityPicker() {
    const level = Number(severitySelect.value);
    $('incident-severity-value').textContent = severityLabels[level] || '—';
    severityTrigger.disabled = severitySelect.disabled || $('incident-update-fields').disabled
      || !detailsLoaded || saving;
    severityChoices.forEach((option, index) => {
      option.setAttribute('aria-selected', String(index === level));
    });
    if (severityTrigger.disabled) closeSeverityMenu();
  }
  function openSeverityMenu() {
    if (severityTrigger.disabled) return;
    focusedSeverity = Math.max(0, Math.min(5, Number(severitySelect.value) || 0));
    severityMenu.hidden = false; severityMenu.showPopover?.();
    severityTrigger.setAttribute('aria-expanded', 'true');
    const anchor = severityTrigger.getBoundingClientRect();
    severityMenu.style.width = anchor.width + 'px';
    const menu = severityMenu.getBoundingClientRect();
    severityMenu.style.left = Math.max(8, Math.min(anchor.left, document.documentElement.clientWidth - anchor.width - 8)) + 'px';
    severityMenu.style.top = Math.max(8, anchor.bottom + menu.height + 6 <= window.innerHeight - 8
      ? anchor.bottom + 6 : anchor.top - menu.height - 6) + 'px';
    severityChoices[focusedSeverity].focus();
  }
  function chooseSeverity(level) {
    if (severityTrigger.disabled) return;
    severitySelect.value = String(level);
    // Keep the original select and its change handler as the form's source of truth.
    severitySelect.dispatchEvent(new Event('change', { bubbles: true }));
    closeSeverityMenu(true);
  }
  severityTrigger.addEventListener('click', () => {
    if (severityMenu.hidden) openSeverityMenu(); else closeSeverityMenu(true);
  });
  severityTrigger.addEventListener('keydown', event => {
    if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      event.preventDefault(); openSeverityMenu();
      if (event.key === 'Home' || event.key === 'End') {
        focusedSeverity = event.key === 'Home' ? 0 : 5; severityChoices[focusedSeverity].focus();
      }
    }
  });
  severityMenu.addEventListener('keydown', event => {
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); closeSeverityMenu(true); return; }
    if (event.key === 'Tab') { closeSeverityMenu(true); return; }
    if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); chooseSeverity(focusedSeverity); return; }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    focusedSeverity = event.key === 'Home' ? 0 : event.key === 'End' ? 5
      : (focusedSeverity + (event.key === 'ArrowDown' ? 1 : 5)) % 6;
    severityChoices[focusedSeverity].focus();
  });
  document.addEventListener('pointerdown', event => {
    if (!severityMenu.hidden && !severityMenu.contains(event.target) && !severityTrigger.contains(event.target)) closeSeverityMenu();
  });
  window.addEventListener('resize', () => closeSeverityMenu());
  document.addEventListener('scroll', event => {
    if (!severityMenu.hidden && !severityMenu.contains(event.target)) closeSeverityMenu();
  }, true);
  function updateSaveState() {
    syncSeverityPicker();
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
  const modalTabs = ['summary', 'trigger', 'update'];
  let occurrenceRows = null, occurrencePage = 1, occurrenceVersion = 0, occurrenceLoading = false;
  function selectModalTab(key, focus = false) {
    closeSeverityMenu();
    hideComments();
    dialog.dataset.activeTab = key;
    if (key === 'trigger') {
      $('incident-trigger-scroll').scrollTop = 0;
      $('incident-trigger-scroll').scrollLeft = 0;
      $('incident-modal-scroll').scrollTop = 0;
    }
    for (const tab of modalTabs) {
      const selected = key === tab;
      $('incident-tab-' + tab).setAttribute('aria-selected', String(selected));
      $('incident-tab-' + tab).tabIndex = selected ? 0 : -1;
      $('incident-panel-' + tab).hidden = !selected;
    }
    if (focus) $('incident-tab-' + key).focus();
    if (key === 'trigger' && detailsLoaded) {
      loadOccurrences();
      updateOccurrenceTimes();
    }
  }
  for (const [index, key] of modalTabs.entries()) {
    $('incident-tab-' + key).addEventListener('click', () => selectModalTab(key));
    $('incident-tab-' + key).addEventListener('keydown', event => {
      let next;
      if (event.key === 'ArrowRight') next = (index + 1) % 3;
      if (event.key === 'ArrowLeft') next = (index + 2) % 3;
      if (event.key === 'Home') next = 0;
      if (event.key === 'End') next = 2;
      if (next !== undefined) { event.preventDefault(); selectModalTab(modalTabs[next], true); }
    });
  }
  const readingNumberFormat = new Intl.NumberFormat('pt-BR', {
    maximumFractionDigits: 2, useGrouping: false,
  });
  function formatItemReading(reading) {
    const raw = String(reading?.value ?? '').trim();
    if (!raw) return '—';
    // Only parse a complete numeric value: labels such as "Down (0)" remain untouched.
    if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(raw)) return raw;
    let value = Number(raw);
    if (!Number.isFinite(value)) return raw;
    let units = String(reading.units || '').replace(/^!/, '').trim();
    if (units === 's') { value *= 1000; units = 'ms'; }
    if (!Number.isFinite(value)) return raw;
    const formatted = readingNumberFormat.format(value);
    return `${formatted}${units ? ' ' + units : ''}`;
  }
  function renderSummary(data) {
    const hosts = (data.hosts || []).map(host => host.name).join(', ') || '—';
    $('incident-dialog-host').replaceChildren(icon('server'), document.createTextNode(hosts));
    $('incident-dialog-severity').textContent = severityLabels[data.severity] || '—';
    $('incident-dialog-severity').style.color = severityColors[data.severity] || '';
    function properties(id, entries) {
      const dl = $(id); dl.replaceChildren();
      for (const [label, value] of entries) {
        const dt = document.createElement('dt'); dt.textContent = label;
        const dd = document.createElement('dd'); dd.textContent = value || '—';
        if (label === 'Severidade') dd.style.color = severityColors[data.severity] || '';
        dl.append(dt, dd);
      }
    }
    properties('incident-event-properties', [
      ['Data de início', formatDate(data.timestamp)],
      ['Severidade', severityLabels[data.severity]],
      ['Status', data.closed ? 'Resolvido' : data.closing ? 'Fechamento solicitado' : 'Incidente ativo'],
      ['Reconhecido', data.acknowledged ? 'Sim' : 'Não'], ['Dados operacionais do evento', data.operationalData],
    ]);
    const reading = data.latestReading;
    $('incident-latest-value').textContent = formatItemReading(reading);
    properties('incident-operational-properties', [
      ['Última coleta', formatDate(reading?.collectedAt)], ['Expressão da trigger', data.expression], ['Origem', 'Zabbix'],
    ]);
    if (data.expression) {
      const dd = $('incident-operational-properties').children[3];
      dd.className = 'incident-expression'; dd.replaceChildren();
      const code = document.createElement('code');
      // Break at expression delimiters without changing the value copied to the clipboard.
      for (const part of data.expression.split(/(?<=[/,()[\]<>])/)) {
        code.append(document.createTextNode(part), document.createElement('wbr'));
      }
      const copy = document.createElement('button'); copy.type = 'button';
      copy.className = 'incident-expression-copy'; copy.title = 'Copiar expressão';
      copy.setAttribute('aria-label', 'Copiar expressão da trigger'); copy.append(icon('copy'));
      const status = document.createElement('span'); status.className = 'incident-copy-status';
      status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
      copy.addEventListener('click', async () => {
        try {
          await navigator.clipboard.writeText(data.expression);
          status.textContent = 'Copiado';
        } catch { status.textContent = 'Não foi possível copiar'; }
        setTimeout(() => { status.textContent = ''; }, 2500);
      });
      dd.append(code, copy, status);
    }
    if (data.tags?.length) {
      const dt = document.createElement('dt'); dt.textContent = 'Tags';
      const tags = document.createElement('dd'); tags.className = 'incident-event-tags';
      for (const tag of data.tags) {
        const badge = document.createElement('span'); badge.className = 'incident-action-badge';
        badge.textContent = `${tag.tag}: ${tag.value}`; tags.append(badge);
      }
      $('incident-event-properties').append(dt, tags);
    }
  }

  const occurrenceDateFormat = new Intl.DateTimeFormat('pt-BR', {
    timeZone: document.querySelector('.incidents-page').dataset.timeZone,
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit', hour12: false,
  });
  const formatOccurrenceDate = timestamp => timestamp > 0
    ? occurrenceDateFormat.format(new Date(timestamp)).replace(',', '') : '—';
  function occurrenceDuration(row) {
    if (!(row.timestamp > 0)) return '—';
    const end = row.resolved ? row.recoveredAt : Date.now();
    if (!(end > 0) || end < row.timestamp) return '—';
    const seconds = Math.floor((end - row.timestamp) / 1000);
    const minutes = Math.floor(seconds / 60), hours = Math.floor(minutes / 60);
    let value;
    if (hours >= 24) value = `${Math.floor(hours / 24)} d ${hours % 24} h`;
    else {
      value = hours ? `${hours} h ${minutes % 60} min` : minutes ? `${minutes} min` : '';
      const remainder = seconds % 60;
      if (remainder) value += `${value ? ' ' : ''}${remainder} s`;
      if (!value) value = '0 min';
    }
    return value;
  }
  function updateOccurrenceTimes() {
    if (!dialog.open || dialog.dataset.activeTab !== 'trigger' || !occurrenceRows) return;
    const visible = occurrenceRows.slice((occurrencePage - 1) * 10, occurrencePage * 10);
    for (const [index, row] of visible.entries()) {
      const cells = $('incident-trigger-rows').children[index]?.children;
      if (!cells) continue;
      cells[3].textContent = duration(row.timestamp);
      if (!row.resolved) cells[4].textContent = occurrenceDuration(row);
    }
  }
  function renderOccurrences() {
    hideComments();
    const body = $('incident-trigger-rows'); body.replaceChildren();
    $('incident-trigger-scroll').scrollTop = 0;
    const rows = occurrenceRows || [];
    $('incident-trigger-state').textContent = rows.length ? '' : 'Nenhum evento encontrado.';
    for (const row of rows.slice((occurrencePage - 1) * 10, occurrencePage * 10)) {
      const tr = document.createElement('tr');
      cell(tr, formatOccurrenceDate(row.timestamp));
      cell(tr, row.resolved ? formatOccurrenceDate(row.recoveredAt) : '—');
      const badge = document.createElement('span'); badge.className = 'incident-occurrence-status';
      badge.dataset.state = row.resolved ? 'resolved' : 'active';
      badge.textContent = row.resolved ? 'Resolvido' : 'Incidente'; cell(tr).append(badge);
      cell(tr, duration(row.timestamp));
      cell(tr, occurrenceDuration(row));
      cell(tr, row.acknowledged ? 'Sim' : 'Não');
      const comments = cell(tr); comments.className = 'incident-observations';
      renderCommentIndicator(comments, row, true); body.append(tr);
    }
    const nav = $('incident-trigger-pagination'); nav.replaceChildren();
    const pages = Math.max(1, Math.ceil(rows.length / 10));
    function button(label, target, disabled, iconName) {
      const node = document.createElement('button'); node.type = 'button'; node.className = 'system-button';
      if (iconName) node.append(icon(iconName));
      node.append(document.createTextNode(label)); node.disabled = disabled;
      node.addEventListener('click', () => { occurrencePage = target; renderOccurrences(); }); nav.append(node);
    }
    const count = document.createElement('span'); count.textContent = `${rows.length} registros`; nav.append(count);
    button('Anterior', occurrencePage - 1, occurrencePage === 1, 'chevron-left');
    const status = document.createElement('span');
    status.className = 'incident-trigger-page-status';
    status.setAttribute('role', 'status');
    status.setAttribute('aria-live', 'polite');
    status.textContent = `Página ${occurrencePage} de ${pages}`;
    nav.append(status);
    button('Próxima', occurrencePage + 1, occurrencePage === pages, 'chevron-right');
  }
  async function loadOccurrences() {
    if (occurrenceRows !== null || occurrenceLoading) return;
    const version = ++occurrenceVersion, id = activeIncident.eventId;
    occurrenceLoading = true;
    $('incident-trigger-retry').hidden = true;
    $('incident-trigger-state').textContent = 'Consultando histórico da trigger…';
    $('incident-panel-trigger').setAttribute('aria-busy', 'true');
    $('incident-trigger-pagination').replaceChildren();
    const body = $('incident-trigger-rows'); body.replaceChildren();
    for (let row = 0; row < 10; row++) {
      const tr = document.createElement('tr'); tr.className = 'incident-skeleton';
      for (let col = 0; col < 7; col++) cell(tr, ' ');
      body.append(tr);
    }
    try {
      const response = await fetch(`/api/incidents/${encodeURIComponent(id)}/occurrences`, { cache: 'no-store' });
      if (!response.ok) throw new Error('Não foi possível consultar o histórico da trigger.');
      const data = await response.json();
      if (version !== occurrenceVersion || !dialog.open) return;
      occurrenceRows = data; occurrencePage = 1; renderOccurrences();
    } catch (error) {
      if (version !== occurrenceVersion || !dialog.open) return;
      body.replaceChildren(); $('incident-trigger-state').textContent = error.message;
      $('incident-trigger-retry').hidden = false;
    } finally {
      if (version === occurrenceVersion) {
        occurrenceLoading = false; $('incident-panel-trigger').setAttribute('aria-busy', 'false');
      }
    }
  }
  $('incident-trigger-retry').addEventListener('click', loadOccurrences);
  function renderIncidentHistory(data) {
    const history = $('incident-history'); history.replaceChildren();
    for (const item of [...data.history].sort((a, b) => b.timestamp - a.timestamp)) {
      const tr = document.createElement('tr');
      const time = document.createElement('time'); time.dateTime = new Date(item.timestamp).toISOString();
      time.textContent = formatOccurrenceDate(item.timestamp); cell(tr).append(time);
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
    $('incident-history-scroll').hidden = data.history.length === 0;
  }
  async function readDetails(focusHistory = false) {
    closeSeverityMenu();
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
      renderIncidentHistory(data); renderSummary(data);
      if (!$('incident-panel-trigger').hidden) loadOccurrences();
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
      if (focusHistory) { selectModalTab('update'); $('incident-history').closest('.table-scroll').focus(); }
    } catch (error) {
      if (version !== detailsVersion || !dialog.open) return;
      $('incident-dialog-state').textContent = error.message; $('incident-details-retry').hidden = false;
    }
  }
  function openIncident(row, trigger, focusHistory = false) {
    hideComments();
    returnFocus = trigger; activeIncident = row; uncertain = false; detailsLoaded = false; hasIncidentDetails = false;
    occurrenceVersion++; occurrenceRows = null; occurrenceLoading = false; occurrencePage = 1;
    $('incident-trigger-rows').replaceChildren(); $('incident-trigger-pagination').replaceChildren();
    $('incident-trigger-state').textContent = 'Aguardando os dados do incidente…';
    $('incident-trigger-retry').hidden = true;
    $('incident-panel-trigger').setAttribute('aria-busy', 'false');
    selectModalTab(focusHistory ? 'update' : 'summary');
    renderSummary(row);
    updateForm.reset(); $('incident-update-error').textContent = '';
    $('incident-history').replaceChildren(); $('incident-history-empty').hidden = true;
    $('incident-history-scroll').hidden = true;
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
  function allowDismiss() {
    if (saving) return false;
    updateSaveState();
    const dirty = $('incident-message').value.trim() || changedSeverity() !== null
      || ($('incident-recognize').checked && !activeIncident?.acknowledged)
      || ($('incident-close').checked && canCloseIncident());
    return !dirty || window.confirm('Descartar alterações não salvas?');
  }
  for (const id of ['incident-dialog-cancel', 'incident-dialog-close']) {
    $(id).addEventListener('click', () => { if (allowDismiss()) dialog.close(); });
  }
  dialog.addEventListener('cancel', event => { if (!allowDismiss()) event.preventDefault(); });
  dialog.addEventListener('close', () => {
    closeSeverityMenu();
    hideComments();
    detailsVersion++; occurrenceVersion++; occurrenceLoading = false; detailsLoaded = false;
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
    } catch (error) {
      // A transport error may happen after the server applied the update.
      uncertain ||= error instanceof TypeError;
      $('incident-update-error').textContent = error.message;
    } finally {
      if (confirmed || uncertain) {
        commentsCache.delete(activeIncident.eventId);
        occurrenceVersion++; occurrenceRows = null; occurrenceLoading = false;
        // Discard snapshots requested before the mutation and fetch the Zabbix state again.
        filterVersion++;
        if (busy) queuedFilters = true;
        else await load();
      }
      await readDetails();
      saving = false; $('incident-dialog-cancel').disabled = false; $('incident-dialog-close').disabled = false;
      $('incident-update-save').setAttribute('aria-busy', 'false');
      $('incident-update-save-label').textContent = 'Atualizar incidente';
      updateSaveState();
      // Keep the refreshed details and update history available after saving.
    }
  });
  function updateLocation() {
    // Names stay in local storage. Large selections also stay off the URL and Referer header.
    const params = new URLSearchParams();
    if (eventId) params.set('eventId', eventId);
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
