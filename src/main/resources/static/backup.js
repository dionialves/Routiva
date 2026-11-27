(() => {
  const $ = id => document.getElementById(id);
  const base = '/api/backups';
  const textDialog = $('backup-text-dialog');
  const previewText = $('backup-text');
  const lineNumbers = $('backup-line-numbers');
  const deleteButton = $('backup-delete');
  const copyButton = $('backup-copy');
  const previewButtons = [...textDialog.querySelectorAll('button')];
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
  let preview;
  let deleteTimer;
  let confirmUntil = 0;
  let deleting = false;
  let copying = false;
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

  let entryOrder;
  TableSort.bind('backup-rows', order => { entryOrder = order; page = 0; renderEntries(); });

  function renderEntries() {
    const groupIds = filterSelection('backup-history-group');
    const hostIds = filterSelection('backup-history-host');
    const filtered = entries.filter(entry => (!hostIds.size || hostIds.has(entry.hostId)) &&
      (!groupIds.size || historyInventory.get(entry.hostId)?.groups.some(group => groupIds.has(group.id))));
    page = Math.min(page, Math.max(0, Math.ceil(filtered.length / pageSize) - 1));
    const start = page * pageSize;
    const rows = $('backup-rows');
    rows.replaceChildren();
    const ordered = TableSort.sort(filtered, entryOrder, [
      entry => entry.hostName, entry => entry.manufacturer, entry => entry.createdAt, entry => entry.filename,
    ]);
    for (const entry of ordered.slice(start, start + pageSize)) {
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
    globalThis.TableState?.done('backup-rows');
    TablePagination.render('backup-pagination', page + 1, pageSize, filtered.length, target => { page = target - 1; renderEntries(); });
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
    globalThis.TableState?.loading('backup-rows');
    try {
      const [history, inventory] = await Promise.all([
        request().then(response => response.json()),
        request('/hosts').then(response => response.json()).catch(() => null),
      ]);
      entries = history;
      if (inventory) historyInventory = new Map(inventory.map(host => [host.id, host]));
      setupHistoryFilters();
      renderEntries();
    } catch (error) {
      globalThis.TableState?.error('backup-rows', error.message);
      throw error;
    }
  }

  function renderPreview(content) {
    const lines = content.split(/\r\n|\n|\r/);
    const numbers = Array.from({ length: lines.length }, (_, index) => index + 1);
    const code = document.createDocumentFragment();
    const tokens = /^\s*(?:[#!;].*|\S+)|"[^"\\]*(?:\\.[^"\\]*)*"|'[^']*'|[\w.-]+(?==)/g;

    for (const [index, line] of lines.entries()) {
      let end = 0;
      for (const match of line.matchAll(tokens)) {
        const value = match[0];
        const token = document.createElement('span');
        const kind = match.index === 0
          ? (/^[#!;]/.test(value.trimStart()) ? 'comment' : 'command')
          : 'parameter';
        token.className = `backup-code-${kind}`;
        token.textContent = value;
        code.append(line.slice(end, match.index), token);
        end = match.index + value.length;
      }
      code.append(line.slice(end));
      if (index < lines.length - 1) code.append('\n');
    }

    lineNumbers.textContent = numbers.join('\n');
    lineNumbers.hidden = false;
    previewText.replaceChildren(code);
  }

  function updatePreviewButtons() {
    for (const button of previewButtons) button.disabled = deleting;
    copyButton.disabled = deleting || copying || preview?.content === undefined;
  }

  function resetDeleteConfirmation() {
    clearTimeout(deleteTimer);
    confirmUntil = 0;
    deleteButton.classList.remove('system-button-danger-solid');
    deleteButton.querySelector('span').textContent = 'Excluir';
    deleteButton.querySelector('use').setAttribute('href', '/icons.svg#trash');
  }

  async function openPreview(entry) {
    if (textDialog.open) return;
    const opened = { entry };
    preview = opened;
    resetDeleteConfirmation();
    $('backup-filename').textContent = entry.filename;
    const extension = /\.([^.]+)$/.exec(entry.filename)?.[1];
    $('backup-format').textContent = extension?.toLowerCase() === 'rsc' ? 'RouterOS (.rsc)' : extension ? `.${extension}` : 'Texto';
    lineNumbers.hidden = true;
    previewText.textContent = 'Carregando…';
    updatePreviewButtons();
    textDialog.showModal();
    $('backup-editor-scroll').scrollTo(0, 0);
    try {
      const content = await (await request(`/${encodeURIComponent(entry.id)}/text`)).text();
      if (textDialog.open && preview === opened) {
        renderPreview(content);
        opened.content = content;
        updatePreviewButtons();
      }
    } catch (error) {
      if (textDialog.open && preview === opened) {
        previewText.textContent = '';
        window.notify(error.message, 'error');
      }
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
  for (const button of document.querySelectorAll('[data-close-backup-dialog], #backup-text-close, #backup-run-close')) {
    button.onclick = () => button.closest('dialog').close();
  }
  runDialog.addEventListener('cancel', event => {
    if (startingRun || activeRunId) event.preventDefault();
  });
  textDialog.addEventListener('cancel', event => {
    if (deleting) event.preventDefault();
    else resetDeleteConfirmation();
  });
  textDialog.addEventListener('close', () => {
    if (textDialog.open) return;
    resetDeleteConfirmation();
    preview = undefined;
    previewText.textContent = '';
    lineNumbers.textContent = '';
  });
  $('backup-download').onclick = () => {
    if (preview && !deleting) window.location.assign(downloadUrl(preview.entry));
  };
  copyButton.onclick = async () => {
    const content = preview?.content;
    if (content === undefined || deleting || copying) return;
    copying = true;
    loading(copyButton, true);
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(content);
      } else {
        const copyOriginal = event => {
          event.clipboardData.setData('text/plain', content);
          event.preventDefault();
        };
        const selection = window.getSelection();
        selection.selectAllChildren(previewText);
        document.addEventListener('copy', copyOriginal, { once: true });
        try {
          if (!document.execCommand('copy')) throw new Error('Selecione o texto e copie pelo teclado.');
        } finally {
          document.removeEventListener('copy', copyOriginal);
          selection.removeAllRanges();
        }
      }
      window.notify('Texto copiado.');
    } catch (error) {
      window.notify(error.message, 'error');
    } finally {
      copying = false;
      loading(copyButton, false);
      updatePreviewButtons();
    }
  };
  deleteButton.addEventListener('keydown', event => {
    if (event.repeat && (event.key === 'Enter' || event.key === ' ')) event.preventDefault();
  });
  deleteButton.onclick = async () => {
    if (!textDialog.open || !preview || deleting) return;
    if (performance.now() >= confirmUntil) {
      clearTimeout(deleteTimer);
      confirmUntil = performance.now() + 5000;
      deleteButton.classList.add('system-button-danger-solid');
      deleteButton.querySelector('span').textContent = 'Confirmar exclusão?';
      deleteButton.querySelector('use').setAttribute('href', '/icons.svg#alert');
      deleteTimer = setTimeout(resetDeleteConfirmation, 5000);
      return;
    }

    clearTimeout(deleteTimer);
    deleting = true;
    loading(deleteButton, true);
    updatePreviewButtons();
    let failed = false;
    try {
      await request(`/${encodeURIComponent(preview.entry.id)}`, { method: 'DELETE' });
    } catch (error) {
      failed = true;
      window.notify(error.message, 'error');
    } finally {
      deleting = false;
      loading(deleteButton, false);
      resetDeleteConfirmation();
      updatePreviewButtons();
    }

    if (failed) {
      deleteButton.focus();
      return;
    }
    textDialog.close();
    window.notify('Backup excluído.');
    try {
      await loadEntries();
    } catch (error) {
      window.notify(error.message, 'error');
    }
  };
  loadEntries().catch(() => {});
  poll();
})();
