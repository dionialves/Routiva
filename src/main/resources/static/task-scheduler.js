(() => {
  const states = {
    PENDING: 'Aguardando', RUNNING: 'Em andamento', COMPLETED: 'Concluída', PARTIAL: 'Concluída com falhas',
    FAILED: 'Falhou', INTERRUPTED: 'Interrompida', SKIPPED: 'Ignorada', MISSED: 'Horário perdido',
  };
  function describe(rule) {
    if (rule.cron) return rule.cron;
    if (rule.frequency === 'INTERVAL') return rule.intervalMinutes === 60
      ? 'A cada hora, no minuto 00' : `A cada ${rule.intervalMinutes} minutos, a partir do minuto 00`;
    if (rule.frequency === 'MONTHLY') return `Dia ${rule.monthDay} às ${rule.time}`
      + (rule.monthDay > 28 ? ' (somente nos meses com esse dia)' : '');
    if (rule.frequency === 'WEEKLY') return rule.weekdays.map(day =>
      ['Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb', 'Dom'][day - 1]).join(', ') + ` às ${rule.time}`;
    return `Todos os dias às ${rule.time}`;
  }
  function date(at, zone) {
    return at == null ? '—' : new Intl.DateTimeFormat('pt-BR', {
      timeZone: zone, day: '2-digit', month: '2-digit', year: 'numeric',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    }).format(new Date(at));
  }
  const historySize = 10;
  const statusLabel = (state, item = false) => state === 'COMPLETED'
    ? item ? 'Sucesso' : 'Concluída com sucesso' : states[state] || state;
  function exportText(execution, items) {
    const zone = execution.zone;
    return [
      'Logs da execução — ' + execution.taskLabel,
      'Horário previsto: ' + date(execution.scheduledAt, zone),
      'Início: ' + date(execution.startedAt, zone),
      'Término: ' + date(execution.finishedAt, zone),
      'Fuso horário: ' + zone,
      'Status: ' + statusLabel(execution.state),
      'Resultado geral: ' + (execution.message || '—'),
      '',
      ...items.map(item => `${item.hostName} — ${statusLabel(item.state, true)} — ${item.message || '—'}`),
      '',
    ].join('\n');
  }
  const cronFields = ['minute', 'hour', 'dayOfMonth', 'month', 'dayOfWeek'];
  function cronFrom(form) {
    const field = name => form.elements.namedItem(name);
    return cronFields.map(name => field(name + 'Type').value === 'all' ? '*' : String(field(name).valueAsNumber)).join(' ');
  }
  function cronValues(rule) {
    if (rule.cron) return rule.cron.split(' ');
    if (rule.frequency === 'INTERVAL')
      return [rule.intervalMinutes === 60 ? '0' : '', '*', '*', '*', '*'];
    const [hour, minute] = rule.time.split(':').map(value => String(Number(value)));
    const weekday = rule.frequency !== 'WEEKLY' || rule.weekdays.length === 7 ? '*'
      : rule.weekdays.length === 1 ? String(rule.weekdays[0] % 7) : '';
    return [minute, hour, rule.frequency === 'MONTHLY' ? String(rule.monthDay) : '*', '*', weekday];
  }
  if (typeof module !== 'undefined') module.exports = { describe, date, cronFrom, cronValues, states, exportText };
  if (typeof document === 'undefined') return;
  const $ = id => document.getElementById(id);
  const form = $('schedule-form'), dialog = $('schedule-dialog'), deletion = $('delete-schedule-dialog');
  const field = name => form.elements.namedItem(name);
  let schedules = [], page = 0, schedulePage = 0, pendingDelete = null, saving = false, loading = false;
  let parameters = {}, deleting = false, defaultZone;
  const notify = (message, type) => window.notify(message, type);
  let selected = null, historyVersion = 0, historyLoading = false, activeTab = 'data', tasks = [];
  async function request(path = '/api', body) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      const token = document.querySelector('meta[name=_csrf]');
      const header = document.querySelector('meta[name=_csrf_header]');
      if (token && header) headers[header.content] = token.content;
    }
    const response = await fetch('/configuracao/agendador' + path, {
      method: body === undefined ? 'GET' : 'POST', headers,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (response.redirected || !response.headers.get('content-type')?.includes('application/json'))
      throw new Error('Sua sessão expirou. Atualize a página e entre novamente.');
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || 'Não foi possível concluir a operação.');
    return result;
  }
  function node(tag, text) {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    return element;
  }
  function cell(row, text) {
    const td = node('td', text);
    row.append(td); return td;
  }
  async function load(silent = false) {
    if (loading) return;
    loading = true;
    globalThis.TableState?.loading('schedule-rows');
    try {
      const data = await request('/api');
      schedules = data.schedules; tasks = data.tasks; defaultZone = data.defaultZone;
      $('new-schedule').disabled = !tasks.some(task => !schedules.some(schedule => schedule.taskKey === task.key));
      const rows = $('schedule-rows'); rows.replaceChildren();
      schedulePage = Math.min(schedulePage, Math.max(0, Math.ceil(schedules.length / 25) - 1));
      for (const schedule of schedules.slice(schedulePage * 25, (schedulePage + 1) * 25)) {
        const row = node('tr'), zone = schedule.rule.zone;
        const heading = node('th'), link = node('a', schedule.taskLabel);
        heading.scope = 'row'; link.href = '#schedule-dialog'; link.className = 'availability-host-name';
        link.setAttribute('aria-haspopup', 'dialog'); link.setAttribute('aria-controls', 'schedule-dialog');
        link.addEventListener('click', event => { event.preventDefault(); open(schedule); });
        heading.append(link); row.append(heading);
        cell(row, describe(schedule.rule));
        cell(row, schedule.enabled ? 'Ativo' : schedule.nextRun === 0 ? 'Somente manual' : 'Pausado');
        cell(row, schedule.enabled ? date(schedule.nextRun, zone) : '—');
        const last = data.latest[schedule.id];
        cell(row, last ? date(last.startedAt, last.zone) : '—');
        const status = cell(row, last ? statusLabel(last.state, true) : 'Ainda não executado');
        if (last) {
          status.className = 'schedule-status-text';
          status.dataset.state = last.state;
        }
        rows.append(row);
      }
      globalThis.TableState?.done('schedule-rows');
      TablePagination.render('schedule-pagination', schedulePage + 1, 25, schedules.length, target => {
        if (!loading) { schedulePage = target - 1; return load(); }
      });
      if (!silent) $('scheduler-feedback').textContent = '';
    } catch (error) { globalThis.TableState?.error('schedule-rows', error.message); }
    finally { loading = false; $('schedule-rows').removeAttribute('aria-busy'); }
  }
  const executionDialog = $('execution-dialog');
  let logs = null, logPage = 0, logsVersion = 0;

  function badge(state, item = false) {
    const label = node('span');
    label.className = 'scheduler-status-badge';
    label.dataset.state = state;
    const symbols = { COMPLETED: '✓', FAILED: '×', INTERRUPTED: '×', PARTIAL: '!', MISSED: '!' };
    if (symbols[state]) {
      const icon = node('span', symbols[state]);
      icon.setAttribute('aria-hidden', 'true');
      label.append(icon);
    }
    label.append(node('span', statusLabel(state, item)));
    return label;
  }
  async function loadHistory() {
    if (!selected || !dialog.open || historyLoading) return;
    const id = selected.id, version = ++historyVersion;
    historyLoading = true;
    const history = $('execution-rows');
    history.setAttribute('aria-busy', 'true');
    globalThis.TableState?.loading('execution-rows');
    try {
      const data = await request(`/api/${encodeURIComponent(id)}/history/summary?page=${page}`);
      if (version !== historyVersion || selected?.id !== id || !dialog.open) return;
      page = data.page;
      history.replaceChildren();
      for (const execution of data.history) {
        const row = node('tr'), zone = execution.zone;
        const button = node('button', date(execution.scheduledAt, zone));
        button.type = 'button';
        button.className = 'system-table-link scheduler-execution-link';
        button.setAttribute('aria-haspopup', 'dialog');
        button.setAttribute('aria-controls', 'execution-dialog');
        button.addEventListener('click', () => openLogs(id, execution));
        cell(row).append(button);
        cell(row, date(execution.startedAt, zone));
        cell(row, date(execution.finishedAt, zone));
        cell(row).append(badge(execution.state));
        history.append(row);
      }
      globalThis.TableState?.done('execution-rows');
      TablePagination.render('history-pagination', page + 1, historySize, data.total, target => {
        if (!historyLoading) { page = target - 1; return loadHistory(); }
      });
      $('history-feedback').textContent = '';
    } catch (error) {
      if (version === historyVersion) {
        globalThis.TableState?.error('execution-rows', error.message);
        notify(error.message, 'error');
      }
    } finally {
      if (version === historyVersion) {
        historyLoading = false;
        history.removeAttribute('aria-busy');
      }
    }
  }
  function renderExecutionResult(execution, items) {
    const result = $('execution-result');
    const summary = /^(\d+) hosts: (\d+) backups salvos, (\d+) com falha\.$/.exec(execution.message || '');
    const allFailed = execution.taskLabel === 'Backup de hosts' && execution.state === 'FAILED'
      && items.length > 0 && items.every(item => item.state === 'FAILED');
    if (!summary && !allFailed) {
      result.textContent = execution.message || '—';
      return;
    }
    const [, total, saved, failed] = summary || [null, items.length, 0, items.length];
    const counts = node('span');
    counts.className = 'execution-result-counts';
    const success = node('span', `${saved} salvos`);
    success.className = 'execution-result-success';
    const failure = node('span', `${failed} com falha`);
    failure.className = 'execution-result-failure';
    counts.append(success, ' · ', failure);
    result.replaceChildren(node('span', `${total} ${Number(total) === 1 ? 'host processado' : 'hosts processados'}`), counts);
  }
  const logFilters = ['logs-filter-success', 'logs-filter-failure'].map($);
  for (const filter of logFilters) {
    filter.addEventListener('change', () => {
      if (!logs) return;
      logPage = 0;
      renderLogs();
    });
  }
  function renderLogs() {
    const rows = $('execution-log-rows');
    rows.replaceChildren();
    const [success, failure] = logFilters.map(filter => filter.checked);
    const filtered = success || failure;
    const items = filtered ? logs.items.filter(({ state }) =>
      (success && state === 'COMPLETED') || (failure && (state === 'FAILED' || state === 'PARTIAL'))
    ) : logs.items;
    for (const item of items.slice(logPage * historySize, (logPage + 1) * historySize)) {
      const row = node('tr');
      row.className = 'execution-log-row';
      const host = node('span', item.hostName);
      host.className = 'execution-log-ellipsis';
      host.title = item.hostName;
      cell(row).append(host);
      const status = badge(item.state, true);
      status.title = statusLabel(item.state, true);
      cell(row).append(status);
      const message = node('span', item.message || '—');
      message.className = 'execution-log-ellipsis';
      if (item.message) message.title = item.message;
      cell(row).append(message);
      rows.append(row);
    }
    globalThis.TableState?.done('execution-log-rows');
    TablePagination.render('logs-pagination', logPage + 1, historySize, items.length, target => {
      logPage = target - 1;
      renderLogs();
    });
  }
  async function openLogs(scheduleId, execution) {
    if (executionDialog.open) return;
    const version = ++logsVersion;
    logs = null; logPage = 0;
    for (const filter of logFilters) filter.checked = false;
    $('execution-description').textContent = execution.taskLabel;
    $('execution-log-rows').replaceChildren();
    globalThis.TableState?.loading('execution-log-rows');
    $('execution-summary').hidden = true;
    $('execution-logs').hidden = false;
    $('execution-export').disabled = true;
    executionDialog.showModal();
    try {
      const result = await request(`/api/${encodeURIComponent(scheduleId)}/history/${encodeURIComponent(execution.id)}`);
      if (version !== logsVersion || !executionDialog.open) return;
      logs = result;
      const { zone, scheduledAt, startedAt, finishedAt, state } = logs.execution;
      $('execution-scheduled').textContent = date(scheduledAt, zone);
      $('execution-started').textContent = date(startedAt, zone);
      $('execution-finished').textContent = date(finishedAt, zone);
      $('execution-status').replaceChildren(badge(state));
      renderExecutionResult(logs.execution, logs.items);
      $('execution-feedback').textContent = '';
      $('execution-summary').hidden = false;
      $('execution-logs').hidden = false;
      $('execution-export').disabled = false;
      renderLogs();
    } catch (error) {
      if (version !== logsVersion || !executionDialog.open) return;
      globalThis.TableState?.error('execution-log-rows', error.message);
      notify(error.message, 'error');
    }
  }
  for (const id of ['execution-close', 'execution-dismiss'])
    $(id).addEventListener('click', () => executionDialog.close());
  executionDialog.addEventListener('close', () => { logsVersion++; logs = null; });
  $('execution-export').addEventListener('click', () => {
    if (!logs) return;
    const url = URL.createObjectURL(new Blob([exportText(logs.execution, logs.items)], { type: 'text/plain;charset=utf-8' }));
    const link = node('a');
    link.href = url;
    link.download = `execucao-${logs.execution.id}.txt`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
  function selectTab(name, focus = false) {
    if (name === 'history' && !selected) return;
    activeTab = name;
    for (const tab of ['data', 'history']) {
      const button = $('schedule-' + tab + '-tab'), chosen = tab === name;
      button.setAttribute('aria-selected', String(chosen)); button.tabIndex = chosen ? 0 : -1;
      $('schedule-' + tab).hidden = !chosen;
      if (chosen && focus) button.focus();
    }
    if (name === 'history') loadHistory();
  }
  for (const name of ['data', 'history']) {
    const button = $('schedule-' + name + '-tab');
    button.addEventListener('click', () => selectTab(name));
    button.addEventListener('keydown', event => {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const target = event.key === 'Home' ? 'data' : event.key === 'End' ? 'history'
        : activeTab === 'data' ? 'history' : 'data';
      selectTab(target === 'history' && !selected ? 'data' : target, true);
    });
  }
  function syncFields() {
    for (const name of cronFields) {
      const enabled = field(name + 'Type').value === 'value';
      field(name).disabled = !enabled;
      field(name).required = enabled;
    }
  }
  function syncActions(active) {
    field('taskKey').disabled = saving || deleting;
    for (const id of ['schedule-close', 'schedule-cancel', 'schedule-delete', 'schedule-run', 'schedule-save']) {
      const button = $(id);
      button.disabled = saving || deleting || (id === 'schedule-run' && !field('taskKey').value);
      button.classList.toggle('system-button-loading', id === active);
      if (id === active) button.setAttribute('aria-busy', 'true');
      else button.removeAttribute('aria-busy');
    }
  }
  function showSchedule(schedule) {
    $('schedule-history-tab').disabled = !schedule;
    $('schedule-delete').hidden = !schedule;
  }
  function updateTitle() {
    const choice = tasks.find(task => task.key === field('taskKey').value);
    $('schedule-title').textContent = choice?.label || selected?.taskLabel || 'Novo agendamento';
    $('schedule-description').textContent = field('taskKey').value === 'host-backup'
      ? 'Configure a programação da tarefa de backup dos hosts.'
      : 'Configure a programação e acompanhe as execuções desta tarefa.';
  }
  function open(schedule) {
    if (dialog.open) return;
    selected = schedule || null; page = 0; historyVersion++; historyLoading = false;
    form.reset(); parameters = schedule?.parameters || {};
    for (const option of field('taskKey').options || [])
      option.disabled = !!option.value && schedules.some(existing => existing.taskKey === option.value && existing.id !== schedule?.id);
    field('taskKey').value = schedule?.taskKey
      || tasks.find(task => !schedules.some(existing => existing.taskKey === task.key))?.key || '';
    showSchedule(schedule);
    updateTitle();
    const values = schedule ? cronValues(schedule.rule) : ['0', '2', '*', '*', '*'];
    cronFields.forEach((name, index) => {
      field(name + 'Type').value = values[index] === '*' ? 'all' : 'value';
      field(name).value = values[index] === '*' ? '' : values[index];
    });
    const notices = [];
    if (values.includes(''))
      notices.push('A programação anterior continua em vigor. Preencha os campos em branco ou selecione Todos para substituí-la.');
    if (schedule && schedule.rule.zone !== defaultZone)
      notices.push(`Ao salvar, a programação passará de ${schedule.rule.zone} para ${defaultZone}.`);
    $('schedule-legacy').textContent = notices.join(' ');
    $('schedule-legacy').hidden = !notices.length;
    $('execution-rows').replaceChildren();
    selectTab('data');
    syncFields();
    syncActions();
    dialog.showModal();
    (schedule ? $('schedule-data-tab') : field('taskKey')).focus();
  }
  $('new-schedule').addEventListener('click', () => open());
  for (const id of ['schedule-cancel', 'schedule-close'])
    $(id).addEventListener('click', () => { if (!saving && !deleting) dialog.close(); });
  dialog.addEventListener('cancel', event => { if (saving || deleting) event.preventDefault(); });
  dialog.addEventListener('close', () => { historyVersion++; selected = null; });
  form.addEventListener('change', () => {
    syncFields();
    syncActions();
    updateTitle();
  });
  form.addEventListener('invalid', () => {
    if (activeTab === 'history') selectTab('data');
  }, true);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (saving || deleting) return;
    saving = true;
    syncActions('schedule-save');
    try {
      const result = await request('/api', {
        id: selected?.id, taskKey: field('taskKey').value, cron: cronFrom(form), parameters,
      });
      dialog.close();
      notify(result.message);
      await load();
    } catch (error) { notify(error.message, 'error'); }
    finally { saving = false; syncActions(); }
  });
  $('schedule-run').addEventListener('click', async () => {
    if (saving || deleting || !field('taskKey').value) return;
    if (!selected && !form.reportValidity()) return;
    saving = true;
    syncActions('schedule-run');
    try {
      if (!selected) {
        const taskKey = field('taskKey').value;
        const created = await request('/api', { taskKey, enabled: false, cron: cronFrom(form), parameters });
        selected = { id: created.id, taskKey, taskLabel: $('schedule-title').textContent };
        showSchedule(selected);
        await load(true);
      }
      const result = await request(`/api/${encodeURIComponent(selected.id)}/run`, {});
      page = 0;
      selectTab('history');
      notify(result.message);
    } catch (error) { notify(error.message, 'error'); }
    finally { saving = false; syncActions(); }
  });
  $('schedule-delete').addEventListener('click', () => {
    if (!selected || saving || deleting) return;
    pendingDelete = selected.id;
    $('delete-schedule-title').textContent = `Excluir “${selected.taskLabel}”?`;
    deletion.showModal();
  });
  $('delete-cancel').addEventListener('click', () => { if (!deleting) deletion.close(); });
  deletion.addEventListener('cancel', event => { if (deleting) event.preventDefault(); });
  $('delete-confirm').addEventListener('click', async () => {
    if (deleting) return;
    deleting = true;
    syncActions();
    const confirm = $('delete-confirm');
    confirm.disabled = true; $('delete-cancel').disabled = true;
    confirm.classList.add('system-button-loading');
    confirm.setAttribute('aria-busy', 'true');
    try {
      const result = await request(`/api/${pendingDelete}/delete`, {});
      deletion.close(); dialog.close();
      notify(result.message);
      await load();
    } catch (error) { notify(error.message, 'error'); }
    finally {
      deleting = false;
      syncActions();
      confirm.disabled = false; $('delete-cancel').disabled = false;
      confirm.classList.remove('system-button-loading');
      confirm.removeAttribute('aria-busy');
    }
  });
  load();
  setInterval(() => {
    if (!document.hidden && !dialog.open && !deletion.open) load(true);
  }, 15000);
})();
