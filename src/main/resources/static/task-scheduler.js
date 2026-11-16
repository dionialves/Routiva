(() => {
  const states = {
    PENDING: 'Aguardando', RUNNING: 'Em andamento', COMPLETED: 'Concluída', PARTIAL: 'Concluída com falhas',
    FAILED: 'Falhou', INTERRUPTED: 'Interrompida', SKIPPED: 'Ignorada', MISSED: 'Horário perdido',
  };
  function describe(rule) {
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
      timeZone: zone, dateStyle: 'short', timeStyle: 'medium',
    }).format(new Date(at));
  }
  function ruleFrom(form) {
    const value = name => form.elements.namedItem(name).value;
    return {
      frequency: value('frequency'), time: value('time'),
      weekdays: Array.from(form.querySelectorAll('[name=weekday]:checked'), input => Number(input.value)),
      monthDay: Number(value('monthDay')), intervalMinutes: Number(value('intervalMinutes')),
      zone: value('zone').trim(),
    };
  }
  if (typeof module !== 'undefined') module.exports = { describe, date, ruleFrom, states };
  if (typeof document === 'undefined') return;
  const $ = id => document.getElementById(id);
  const form = $('schedule-form'), dialog = $('schedule-dialog'), deletion = $('delete-schedule-dialog');
  const field = name => form.elements.namedItem(name);
  let schedules = [], page = 0, schedulePage = 0, pendingDelete = null, saving = false, loading = false;
  let previewVersion = 0, previewTimer, parameters = {}, deleting = false;
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
  function cell(row, text, secondary) {
    const td = node('td', text);
    if (secondary) td.append(node('small', secondary));
    row.append(td); return td;
  }
  function stateCell(row, execution) {
    const td = cell(row, execution ? states[execution.state] || execution.state : 'Ainda não executado');
    td.className = 'scheduler-state'; td.dataset.state = execution?.state || '';
    return td;
  }
  function empty(body, columns, text) {
    const row = node('tr'), td = cell(row, text); td.colSpan = columns; body.append(row);
  }
  async function load(silent = false) {
    if (loading) return;
    loading = true;
    $('schedule-rows').setAttribute('aria-busy', 'true');
    try {
      const data = await request('/api');
      schedules = data.schedules; tasks = data.tasks;
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
        cell(row, describe(schedule.rule), zone);
        cell(row, schedule.enabled ? 'Ativo' : 'Pausado');
        cell(row, schedule.enabled ? date(schedule.nextRun, zone) : '—');
        const last = data.latest[schedule.id];
        const lastCell = stateCell(row, last);
        if (last) lastCell.append(node('small', date(last.scheduledAt, last.zone)));
        rows.append(row);
      }
      if (!schedules.length) empty(rows, 5, 'Nenhum agendamento cadastrado.');
      $('schedule-previous').disabled = schedulePage === 0;
      $('schedule-next').disabled = (schedulePage + 1) * 25 >= schedules.length;
      $('schedule-page').textContent = `Exibindo ${schedules.length ? schedulePage * 25 + 1 : 0}–${Math.min((schedulePage + 1) * 25, schedules.length)} de ${schedules.length} registros`;
      if (!silent) $('scheduler-feedback').textContent = '';
    } catch (error) { $('scheduler-feedback').textContent = error.message; }
    finally { loading = false; $('schedule-rows').removeAttribute('aria-busy'); }
  }
  async function loadHistory() {
    if (!selected || !dialog.open) return;
    const id = selected.id, version = ++historyVersion;
    historyLoading = true; $('refresh-history').disabled = true;
    $('history-feedback').textContent = 'Carregando execuções…';
    $('execution-rows').replaceChildren();
    $('history-previous').disabled = true; $('history-next').disabled = true;
    try {
      const data = await request(`/api/${encodeURIComponent(id)}/history?page=${page}`);
      if (version !== historyVersion || selected?.id !== id || !dialog.open) return;
      page = data.page;
      const history = $('execution-rows'); history.replaceChildren();
      for (const execution of data.history) {
        const row = node('tr'), zone = execution.zone;
        cell(row, date(execution.scheduledAt, zone), zone);
        cell(row, date(execution.startedAt, zone)); cell(row, date(execution.finishedAt, zone));
        stateCell(row, execution);
        const detailsCell = cell(row, execution.message);
        const items = data.details?.[execution.id] || [];
        if (items.length) {
          const disclosure = node('details'), summary = node('summary', `Resultados por AP (${items.length})`);
          const list = node('ul');
          for (const item of items) list.append(node('li', `${item.hostName}: ${states[item.state] || item.state} — ${item.message}`));
          disclosure.append(summary, list); detailsCell.append(disclosure);
        }
        history.append(row);
      }
      if (!data.history.length) empty(history, 5, 'Nenhuma execução registrada.');
      $('history-previous').disabled = page === 0;
      $('history-next').disabled = (page + 1) * 25 >= data.total;
      $('history-page').textContent = `Exibindo ${data.total ? page * 25 + 1 : 0}–${Math.min((page + 1) * 25, data.total)} de ${data.total} registros`;
      $('history-feedback').textContent = '';
    } catch (error) {
      if (version === historyVersion) $('history-feedback').textContent = error.message;
    } finally {
      if (version === historyVersion) { historyLoading = false; $('refresh-history').disabled = false; }
    }
  }
  function selectTab(name, focus = false) {
    if (name === 'history' && !selected) return;
    activeTab = name;
    for (const tab of ['data', 'history']) {
      const button = $('schedule-' + tab + '-tab'), chosen = tab === name;
      button.setAttribute('aria-selected', String(chosen)); button.tabIndex = chosen ? 0 : -1;
      $('schedule-' + tab).hidden = !chosen;
      if (chosen && focus) button.focus();
    }
    $('schedule-save').hidden = name !== 'data';
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
  function visibility() {
    const frequency = field('frequency').value;
    $('schedule-time-field').hidden = frequency === 'INTERVAL';
    field('time').disabled = frequency === 'INTERVAL';
    $('schedule-weekdays').hidden = frequency !== 'WEEKLY';
    $('schedule-month-field').hidden = frequency !== 'MONTHLY';
    field('monthDay').disabled = frequency !== 'MONTHLY';
    $('schedule-interval-field').hidden = frequency !== 'INTERVAL';
  }
  function preview() {
    visibility();
    const version = ++previewVersion;
    clearTimeout(previewTimer);
    $('schedule-preview').replaceChildren(); $('schedule-summary').textContent = 'Calculando próximas execuções…';
    previewTimer = setTimeout(async () => {
      try {
        const rule = ruleFrom(form), result = await request('/preview', rule);
        if (version !== previewVersion || !dialog.open) return;
        $('schedule-summary').textContent = result.description + ' · ' + rule.zone
          + (field('enabled').checked ? '' : ' · Pausado: horários apenas para referência.');
        $('schedule-preview').replaceChildren(...result.times.map(at => node('li', date(at, rule.zone))));
      } catch (error) { if (version === previewVersion) $('schedule-summary').textContent = error.message; }
    }, 200);
  }
  function open(schedule) {
    selected = schedule || null; page = 0; historyVersion++; historyLoading = false;
    form.reset(); parameters = schedule?.parameters || {};
    field('id').value = schedule?.id || '';
    $('schedule-title').textContent = schedule ? schedule.taskLabel : 'Novo agendamento';
    $('schedule-description').textContent = schedule ? 'Configure a programação e acompanhe as execuções desta tarefa.' : 'Escolha uma tarefa e configure sua programação.';
    $('schedule-history-tab').disabled = !schedule;
    $('schedule-toggle').hidden = !schedule; $('schedule-delete').hidden = !schedule;
    $('schedule-toggle').querySelector('span').textContent = schedule?.enabled ? 'Pausar' : 'Ativar';
    $('schedule-toggle').disabled = !!schedule && !schedule.enabled && !tasks.some(task => task.key === schedule.taskKey);
    selectTab('data');
    if (schedule) {
      for (const name of ['taskKey']) field(name).value = schedule[name];
      for (const name of ['frequency', 'time', 'monthDay', 'intervalMinutes', 'zone'])
        field(name).value = schedule.rule[name] ?? '';
      for (const checkbox of form.querySelectorAll('[name=weekday]'))
        checkbox.checked = schedule.rule.weekdays.includes(Number(checkbox.value));
      field('enabled').checked = schedule.enabled;
    }
    for (const option of field('taskKey').options || [])
      option.disabled = !!option.value && schedules.some(existing => existing.taskKey === option.value && existing.id !== schedule?.id);
    if (!schedule) field('taskKey').value = tasks.find(task => !schedules.some(existing => existing.taskKey === task.key))?.key || '';
    $('schedule-run').disabled = !field('taskKey').value;
    $('schedule-execution-help').textContent = schedule
      ? 'Executar agora usa os dados salvos e mantém a próxima execução programada. Salve antes para aplicar alterações.'
      : 'Executar agora salva este agendamento como pausado e inicia a tarefa uma vez.';
    $('schedule-error').textContent = '';
    dialog.showModal(); preview(); field('taskKey').focus();
  }
  $('new-schedule').addEventListener('click', () => open());
  for (const id of ['schedule-cancel', 'schedule-close'])
    $(id).addEventListener('click', () => { if (!saving) dialog.close(); });
  dialog.addEventListener('cancel', event => { if (saving) event.preventDefault(); });
  dialog.addEventListener('close', () => { previewVersion++; historyVersion++; selected = null; clearTimeout(previewTimer); });
  form.addEventListener('input', () => {
    $('schedule-run').disabled = saving || !field('taskKey').value;
    preview();
  });
  form.addEventListener('submit', async event => {
    event.preventDefault(); if (saving) return;
    if (activeTab !== 'data') return;
    saving = true; $('schedule-save').disabled = true; $('schedule-cancel').disabled = true;
    $('schedule-error').textContent = '';
    try {
      const result = await request('/api', {
        id: field('id').value, taskKey: field('taskKey').value,
        enabled: field('enabled').checked, rule: ruleFrom(form), parameters,
      });
      dialog.close(); await load(); $('scheduler-feedback').textContent = result.message;
    } catch (error) { $('schedule-error').textContent = error.message; }
    finally { saving = false; $('schedule-save').disabled = false; $('schedule-cancel').disabled = false; }
  });
  $('schedule-run').addEventListener('click', async () => {
    if (saving || deleting || !field('taskKey').value) return;
    if (!selected && !form.reportValidity()) return;
    saving = true; $('schedule-run').disabled = true; $('schedule-save').disabled = true;
    $('schedule-error').textContent = '';
    try {
      if (!selected) {
        const created = await request('/api', {
          taskKey: field('taskKey').value, enabled: false, rule: ruleFrom(form), parameters,
        });
        await load(true);
        const createdSchedule = schedules.find(schedule => schedule.id === created.id);
        if (!createdSchedule) throw new Error('Agendamento salvo. Atualize a página para executar.');
        open(createdSchedule);
      }
      const result = await request(`/api/${encodeURIComponent(selected.id)}/run`, {});
      page = 0; selectTab('history');
      $('scheduler-feedback').textContent = result.message;
    } catch (error) { selectTab('data'); $('schedule-error').textContent = error.message; }
    finally { saving = false; $('schedule-run').disabled = false; $('schedule-save').disabled = false; }
  });
  $('schedule-toggle').addEventListener('click', async () => {
    if (!selected || saving) return;
    saving = true; $('schedule-toggle').disabled = true;
    try {
      const result = await request(`/api/${selected.id}/${selected.enabled ? 'pause' : 'enable'}`, {});
      dialog.close(); await load(); $('scheduler-feedback').textContent = result.message;
    } catch (error) { $('schedule-error').textContent = error.message; selectTab('data'); }
    finally { saving = false; $('schedule-toggle').disabled = false; }
  });
  $('schedule-delete').addEventListener('click', () => {
    if (!selected || saving) return;
    pendingDelete = selected.id; $('delete-error').textContent = '';
    $('delete-schedule-title').textContent = `Excluir “${selected.taskLabel}”?`; deletion.showModal();
  });
  $('refresh-history').addEventListener('click', loadHistory);
  $('delete-cancel').addEventListener('click', () => { if (!deleting) deletion.close(); });
  deletion.addEventListener('cancel', event => { if (deleting) event.preventDefault(); });
  $('delete-confirm').addEventListener('click', async () => {
    if (deleting) return;
    deleting = true; $('delete-confirm').disabled = true; $('delete-cancel').disabled = true;
    try {
      const result = await request(`/api/${pendingDelete}/delete`, {});
      deletion.close(); dialog.close(); await load(); $('scheduler-feedback').textContent = result.message;
    } catch (error) { $('delete-error').textContent = error.message; }
    finally { deleting = false; $('delete-confirm').disabled = false; $('delete-cancel').disabled = false; }
  });
  $('schedule-previous').addEventListener('click', () => { if (!loading) { schedulePage--; load(); } });
  $('schedule-next').addEventListener('click', () => { if (!loading) { schedulePage++; load(); } });
  $('history-previous').addEventListener('click', () => { if (!historyLoading) { page--; loadHistory(); } });
  $('history-next').addEventListener('click', () => { if (!historyLoading) { page++; loadHistory(); } });
  load();
  setInterval(() => { if (!document.hidden && !deletion.open) {
      if (!dialog.open) load(true);
      else if (activeTab === 'history' && !historyLoading) loadHistory();
    } }, 15000);
})();
