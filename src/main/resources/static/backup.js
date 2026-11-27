(() => {
  const $ = id => document.getElementById(id);
  const base = '/api/backups';
  const textDialog = $('backup-text-dialog');
  const deleteDialog = $('backup-delete-dialog');
  const runButton = $('backup-run');
  const runDialog = $('backup-run-dialog');
  const runHost = $('backup-run-host');
  const runSubmit = $('backup-run-submit');
  const runPicker = window.setupGroupPicker(runHost, 'backup-run-host-label', 'Pesquisar host', {
    listLabel: 'Hosts', emptyText: 'Nenhum host encontrado.',
  });
  const date = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'medium' });
  const eventDate = new Intl.DateTimeFormat('pt-BR', {
    day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
  });
  const runEvents = $('backup-run-events');
  const runStatus = $('backup-run-status');
  const runIcons = { running: 'refresh', success: 'check-circle', error: 'error' };
  let startingRun = false;
  let activeRunId;
  const pageSize = 50;
  let entries = [];
  let historyInventory = new Map();
  const filterPickers = {};
  let page = 0;
  let currentEntry;
  let pollTimer;

  const downloadUrl = entry => `${base}/${encodeURIComponent(entry.id)}/download`;

  async function request(path = '', options = {}) {
    const response = await fetch(base + path, options);
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.message || 'Não foi possível concluir a operação de backup.');
    }
    return response;
  }

  function loading(button, active) {
    button.disabled = active;
    button.classList.toggle('system-button-loading', active);
    button.setAttribute('aria-busy', active);
  }

  async function action(button, execute) {
    loading(button, true);
    try {
      await execute();
    } catch (error) {
      window.notify(error.message, 'error');
    } finally {
      loading(button, false);
    }
  }

  function cell(row, value) {
    const td = row.insertCell();
    if (value instanceof Node) td.append(value);
    else td.textContent = value;
    return td;
  }

  function renderEntries() {
    const groupIds = filterSelection('backup-history-group');
    const hostIds = filterSelection('backup-history-host');
    const filtered = entries.filter(entry => (!hostIds.size || hostIds.has(entry.hostId)) &&
      (!groupIds.size || historyInventory.get(entry.hostId)?.groups.some(group => groupIds.has(group.id))));
    page = Math.min(page, Math.max(0, Math.ceil(filtered.length / pageSize) - 1));
    const start = page * pageSize;
    const rows = $('backup-rows');
    rows.replaceChildren();
    for (const entry of filtered.slice(start, start + pageSize)) {
      const row = rows.insertRow();
      const heading = document.createElement('th');
      heading.scope = 'row';
      const link = document.createElement('a');
      link.className = 'availability-host-name';
      link.href = downloadUrl(entry);
      link.textContent = entry.hostName;
      if (entry.text) {
        link.setAttribute('aria-haspopup', 'dialog');
        link.setAttribute('aria-controls', 'backup-text-dialog');
        link.addEventListener('click', event => {
          event.preventDefault();
          openPreview(entry).catch(error => window.notify(error.message, 'error'));
        });
      }
      heading.append(link);
      row.append(heading);
      cell(row, entry.manufacturer);
      cell(row, date.format(entry.createdAt));
      cell(row, entry.filename);
    }
    $('backup-empty').textContent = 'Nenhum backup encontrado.';
    $('backup-empty').hidden = filtered.length > 0;
    $('backup-prev').disabled = page === 0;
    $('backup-next').disabled = start + pageSize >= filtered.length;
    $('backup-count').textContent =
      `Exibindo ${filtered.length ? start + 1 : 0}–${Math.min(start + pageSize, filtered.length)} de ${filtered.length} registros`;
  }

  function filterSelection(kind) {
    return new Set([...$(`incident-${kind}`).selectedOptions].map(option => option.value));
  }

  function setupHistoryFilters() {
    const groups = new Map();
    const choices = new Map();
    for (const entry of entries) {
      const host = historyInventory.get(entry.hostId);
      choices.set(entry.hostId, { id: entry.hostId, name: host?.name ?? entry.hostName });
      for (const group of host?.groups ?? []) groups.set(group.id, group);
    }
    for (const [kind, options, allLabel, selectedLabel] of [
      ['backup-history-group', groups, 'Todos os grupos', 'grupos selecionados'],
      ['backup-history-host', choices, 'Todos os hosts', 'hosts selecionados'],
    ]) {
      setupFilter(kind, options.values(), allLabel, selectedLabel, () => {
        page = 0;
        renderEntries();
      });
    }
  }

  function setupFilter(kind, options, allLabel, selectedLabel, onChange) {
    const select = $(`incident-${kind}`);
    const selectedIds = filterSelection(kind);
    const sorted = [...options].sort((a, b) => a.name.localeCompare(b.name, 'pt-BR'));
    filterPickers[kind]?.dispose();
    select.replaceChildren(...sorted.map(choice => new Option(choice.name, choice.id, false, selectedIds.has(choice.id))));
    $(`incident-${kind}-options`).replaceChildren();
    filterPickers[kind] = window.setupMultiPicker(kind, select, allLabel, onChange,
      { allText: allLabel, selectedLabel, preserveSelection: true });
    select.onchange = () => { filterPickers[kind](); onChange(); };
  }

  async function loadEntries() {
    const [history, inventory] = await Promise.all([
      request().then(response => response.json()),
      request('/hosts').then(response => response.json()).catch(() => null),
    ]);
    entries = history;
    if (inventory) historyInventory = new Map(inventory.map(host => [host.id, host]));
    setupHistoryFilters();
    renderEntries();
  }

  async function openPreview(entry) {
    if (textDialog.open) return;
    currentEntry = entry;
    $('backup-filename').textContent = entry.filename;
    $('backup-text').textContent = 'Carregando…';
    $('backup-copy').disabled = true;
    textDialog.showModal();
    try {
      const content = await (await request(`/${encodeURIComponent(entry.id)}/text`)).text();
      if (textDialog.open && currentEntry === entry) {
        $('backup-text').textContent = content;
        $('backup-copy').disabled = false;
      }
    } catch (error) {
      if (currentEntry === entry) $('backup-text').textContent = error.message;
    }
  }

  function setRunBusy(busy) {
    loading(runSubmit, busy);
    runSubmit.disabled = busy || !runHost.value;
    runHost.disabled = busy;
    runHost.parentElement.querySelector('.group-picker-trigger').disabled = busy;
    $('backup-run-close').disabled = busy;
    $('backup-run-cancel').disabled = busy;
  }

  function runNotice(message = '') {
    const notice = $('backup-run-notice');
    notice.textContent = message;
    notice.hidden = !message;
  }

  function setEventTime(time, timestamp) {
    time.dateTime = new Date(timestamp).toISOString();
    time.textContent = eventDate.format(timestamp).replace(',', '');
  }

  function renderRunStatus(state, timestamp) {
    const message = {
      running: 'Backup em andamento',
      success: 'Backup concluído com sucesso',
      error: 'Falha ao realizar backup',
    }[state];
    runStatus.hidden = false;
    runStatus.dataset.state = state;
    const label = runStatus.querySelector('strong');
    if (label.textContent !== message) label.textContent = message;
    runStatus.querySelector('use').setAttribute('href', `/icons.svg#${runIcons[state]}`);
    setEventTime(runStatus.querySelector('time'), timestamp);
  }

  function setStepState(item, state) {
    if (item.dataset.state === state) return;
    item.dataset.state = state;
    const icon = item.firstElementChild;
    icon.setAttribute('aria-label', { running: 'Em execução', success: 'Concluída', error: 'Falhou' }[state]);
    icon.firstElementChild.setAttribute('href', `/icons.svg#${state === 'success' ? 'check' : runIcons[state]}`);
  }

  function renderRunEvents(status) {
    const state = status.running ? 'running' : status.saved ? 'success' : 'error';
    const events = status.events;
    const last = events.at(-1);
    if (!last) return;
    renderRunStatus(state, last.timestamp);
    runEvents.hidden = false;
    for (const event of events.slice(runEvents.childElementCount)) {
      const item = document.createElement('li');
      const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      icon.setAttribute('class', 'ui-icon backup-run-step-icon');
      icon.setAttribute('role', 'img');
      icon.setAttribute('viewBox', '0 0 24 24');
      icon.append(document.createElementNS('http://www.w3.org/2000/svg', 'use'));
      const time = document.createElement('time');
      setEventTime(time, event.timestamp);
      const message = document.createElement('span');
      message.textContent = event.message;
      item.append(icon, time, message);
      setStepState(item, event === last ? state : 'success');
      runEvents.append(item);
    }
    for (const [index, item] of Array.from(runEvents.children).entries()) {
      setStepState(item, index === events.length - 1 ? state : 'success');
    }
  }

  function resetRunProgress() {
    runEvents.replaceChildren();
    runEvents.hidden = true;
    runStatus.hidden = true;
    runNotice();
  }

  function showStatus(status) {
    if (activeRunId && activeRunId === status.runId) {
      renderRunEvents(status);
      runNotice();
      if (!status.running) {
        activeRunId = undefined;
        setRunBusy(false);
      }
    } else if (activeRunId) {
      activeRunId = undefined;
      setRunBusy(false);
      runStatus.hidden = true;
      runNotice('O andamento desta execução não está mais disponível.');
    }
    loading(runButton, status.running);
    clearTimeout(pollTimer);
    if (status.running) pollTimer = setTimeout(poll, 2000);
  }

  async function poll() {
    const requestedRunId = activeRunId;
    try {
      const status = await (await request('/run')).json();
      if (startingRun || requestedRunId !== activeRunId) return;
      const wasRunning = runButton.disabled;
      showStatus(status);
      if (wasRunning && !status.running) await loadEntries();
    } catch (error) {
      if (startingRun || requestedRunId !== activeRunId) return;
      if (activeRunId) {
        runNotice('Não foi possível atualizar o progresso. Tentando novamente…');
        pollTimer = setTimeout(poll, 2000);
        return;
      }
      loading(runButton, false);
      window.notify(error.message, 'error');
    }
  }

  runButton.onclick = () => action(runButton, async () => {
    const data = await (await request('/hosts')).json();
    const available = data.filter(host => host.ready);
    runHost.replaceChildren(new Option('Selecione um host', ''), ...available.map(host => new Option(host.name, host.id)));
    runPicker.sync();
    setRunBusy(false);
    resetRunProgress();
    $('backup-run-progress').hidden = true;
    $('backup-run-empty').hidden = available.length > 0;
    runDialog.showModal();
  });
  runHost.onchange = () => { runSubmit.disabled = !runHost.value; };
  $('backup-run-form').onsubmit = async event => {
    event.preventDefault();
    if (!runHost.value || startingRun || activeRunId) return;
    startingRun = true;
    loading(runButton, true);
    setRunBusy(true);
    resetRunProgress();
    $('backup-run-progress').hidden = false;
    renderRunStatus('running', Date.now());
    try {
      const response = await request('/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ hostId: runHost.value }),
      });
      const status = await response.json();
      activeRunId = status.runId;
      showStatus(status);
    } catch (error) {
      loading(runButton, false);
      setRunBusy(false);
      renderRunStatus('error', Date.now());
      runNotice(error.message);
    } finally {
      startingRun = false;
    }
  };
  for (const button of document.querySelectorAll('[data-close-backup-dialog], #backup-text-close, #backup-delete-close, #backup-run-close')) {
    button.onclick = () => button.closest('dialog').close();
  }
  runDialog.addEventListener('cancel', event => {
    if (startingRun || activeRunId) event.preventDefault();
  });
  textDialog.addEventListener('close', () => {
    if (textDialog.open) return;
    currentEntry = undefined;
    $('backup-text').textContent = '';
  });
  $('backup-download').onclick = () => {
    if (currentEntry) window.location.assign(downloadUrl(currentEntry));
  };
  $('backup-copy').onclick = event => action(event.currentTarget, async () => {
    const content = $('backup-text');
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(content.textContent);
    } else {
      const selection = window.getSelection();
      selection.selectAllChildren(content);
      if (!document.execCommand('copy')) throw new Error('Selecione o texto e copie pelo teclado.');
      selection.removeAllRanges();
    }
    window.notify('Texto copiado.');
  });
  $('backup-delete').onclick = () => {
    $('backup-delete-name').textContent = currentEntry.filename;
    deleteDialog.showModal();
  };
  $('backup-delete-confirm').onclick = event => {
    const entry = currentEntry;
    action(event.currentTarget, async () => {
      await request(`/${encodeURIComponent(entry.id)}`, { method: 'DELETE' });
      deleteDialog.close();
      if (currentEntry === entry) textDialog.close();
      window.notify('Backup excluído.');
      await loadEntries();
    });
  };
  $('backup-prev').onclick = () => { page--; renderEntries(); };
  $('backup-next').onclick = () => { page++; renderEntries(); };
  loadEntries().catch(error => { $('backup-empty').textContent = error.message; });
  poll();
})();
