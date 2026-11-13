(() => {
  function statistics(samples) {
    const valid = samples.filter(p => Number.isFinite(p.latency));
    const measured = samples.filter(p => Number.isFinite(p.loss));
    return {
      mean: valid.length ? valid.reduce((sum, p) => sum + p.latency, 0) / valid.length : null,
      min: valid.length ? Math.min(...valid.map(p => p.latency)) : null,
      max: valid.length ? Math.max(...valid.map(p => p.latency)) : null,
      loss: measured.length ? measured.reduce((sum, p) => sum + p.loss, 0) / measured.length : null,
      replies: valid.length,
      total: samples.length,
    };
  }
  function summarize(results) {
    const ap = statistics(results[0]?.samples || []);
    const clients = results.slice(1).map(result => {
      const stats = statistics(result.samples || []);
      return { ...result, stats, delta: stats.mean !== null && ap.mean !== null ? stats.mean - ap.mean : null };
    });
    const measured = clients.filter(client => client.stats.mean !== null);
    const mean = measured.length ? measured.reduce((sum, client) => sum + client.stats.mean, 0) / measured.length : null;
    const packets = clients.flatMap(client => client.samples || []);
    return {
      ap, clients, mean, delta: mean !== null && ap.mean !== null ? mean - ap.mean : null,
      measured: measured.length, loss: statistics(packets).loss,
      noReply: clients.filter(client => !client.target.warning && client.stats.loss !== null && client.stats.replies === 0).length,
      unavailable: clients.filter(client => client.target.warning || client.stats.loss === null).length,
      worst: clients.filter(client => client.delta !== null)
        .sort((a, b) => b.delta - a.delta || (b.stats.loss ?? 0) - (a.stats.loss ?? 0) || a.target.name.localeCompare(b.target.name)).slice(0, 3),
    };
  }
  // Initial interpretation thresholds; independent of collector and ping calculations.
  const interpretation = { attentionMs: 2, criticalMs: 5, lossPercent: 2 };
  function classify(result, completed = false) {
    const { stats, delta, target } = result;
    if (result.reference) return { kind: 'reference', label: 'Referência' };
    if (target.warning) return { kind: 'neutral', label: 'Sem IP válido' };
    if (!stats.total) return { kind: 'neutral', label: completed ? 'Sem coleta' : 'Aguardando' };
    if (stats.loss === null) return { kind: 'neutral', label: 'Falha na coleta' };
    if (!stats.replies) return { kind: 'neutral', label: 'Sem resposta' };
    if (delta !== null && delta > interpretation.criticalMs) return { kind: 'critical', label: 'Crítico' };
    if ((delta !== null && delta > interpretation.attentionMs) || stats.loss > interpretation.lossPercent)
      return { kind: 'attention', label: 'Atenção' };
    if (delta === null) return { kind: 'neutral', label: 'Sem referência' };
    return { kind: 'normal', label: 'Normal' };
  }
  function selectClients(clients, query, onlyLoss, order) {
    const normalize = value => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
    const needle = normalize(query.trim());
    const value = client => order === 'loss' ? client.stats.loss : order === 'mean' ? client.stats.mean : client.delta;
    return clients.filter(client => (!onlyLoss || client.stats.loss > 0)
      && normalize([client.target.name, client.target.ip, client.target.mac].join(' ')).includes(needle))
      .sort((a, b) => {
        if (order === 'name') return a.target.name.localeCompare(b.target.name, 'pt-BR');
        const av = value(a), bv = value(b);
        if (av === null && bv !== null) return 1;
        if (bv === null && av !== null) return -1;
        return (av === null ? 0 : order === 'delta-asc' ? av - bv : bv - av)
          || a.target.name.localeCompare(b.target.name, 'pt-BR');
      });
  }
  function coverageOf(clients) {
    const coverage = { total: clients.length, responding: 0, noReply: 0, noIp: 0, unmeasured: 0 };
    for (const client of clients) {
      if (client.target.warning) coverage.noIp++;
      else if (client.stats.replies > 0) coverage.responding++;
      else if (client.stats.loss !== null) coverage.noReply++;
      else coverage.unmeasured++;
    }
    return coverage;
  }
  function positiveTop(clients) {
    return clients.filter(client => !client.target.warning && client.stats.mean !== null && client.delta > 0)
      .sort((a, b) => b.delta - a.delta || a.target.name.localeCompare(b.target.name)).slice(0, 3);
  }
  function completionConfirmed(view) { return view.completed === true && view.failed !== true; }
  function packetProgress(view) {
    const targets = view.results.filter(result => !result.target.warning);
    return targets.length ? Math.min(view.packetCount, ...targets.map(result => result.samples.length)) : 0;
  }
  if (typeof module !== 'undefined') module.exports = { statistics, summarize, classify, selectClients, coverageOf, positiveTop, completionConfirmed, packetProgress };
  if (typeof window !== 'undefined') window.ApLatency = { statistics, summarize, classify, selectClients, coverageOf, positiveTop, completionConfirmed, packetProgress };
  if (typeof document === 'undefined') return;
  const dialog = document.getElementById('ap-latency-modal');
  if (!dialog) return;
  const menu = document.getElementById('ap-settings-menu');
  const settings = document.getElementById('ap-settings');
  const start = document.getElementById('ap-latency-start');
  const close = document.getElementById('ap-latency-close');
  const packetPicker = document.getElementById('latency-packets');
  let packetGoal = Number(packetPicker.value);
  const state = document.getElementById('ap-latency-state');
  const body = document.getElementById('ap-latency-results');
  const base = '/api/ap/' + encodeURIComponent(document.querySelector('[data-host-id]').dataset.hostId) + '/latency';
  let session = null, timer = null, busy = false, generation = 0;
  const format = (value, unit = ' ms') => value === null ? '—' : value.toLocaleString('pt-BR', { minimumFractionDigits: unit === ' ms' ? 2 : 0, maximumFractionDigits: 2 }) + unit;
  const serverTimes = new WeakMap();
  async function request(path = '', method = 'GET') {
    const headers = {};
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content;
    if (token && header) headers[header] = token;
    const response = await fetch(base + path, { method, headers, cache: 'no-store' });
    if (response.status === 204) return;
    const data = await response.json();
    if (!response.ok) {
      if (method === 'DELETE' && response.status === 404) return;
      const error = new Error(data.message || 'Não foi possível consultar a avaliação.');
      error.status = response.status;
      throw error;
    }
    if (data && typeof data === 'object') serverTimes.set(data, Date.parse(response.headers.get('Date')));
    return data;
  }
  const search = document.getElementById('latency-search');
  const lossOnly = document.getElementById('latency-loss-only');
  const sort = document.getElementById('latency-sort');
  const rows = new Map();
  let latest = [], finished = false;
  const setText = (element, value) => { if (element.textContent !== value) element.textContent = value; };
  const text = (id, value) => setText(document.getElementById(id), value);
  const statusPaths = {
    reference: '<circle cx="12" cy="12" r="5"/><path d="M12 2v4m0 12v4M2 12h4m12 0h4"/>',
    normal: '<path d="m4 12 5 5L20 6"/>',
    attention: '<path d="m12 3 10 18H2ZM12 9v5m0 3v.1"/>',
    critical: '<path d="m12 3 10 18H2ZM12 9v5m0 3v.1"/>',
    neutral: '<circle cx="12" cy="12" r="9"/><path d="M7 12h10"/>',
  };
  function execution(label, kind, remaining) {
    text('latency-execution-status', label);
    document.getElementById('latency-execution-status').dataset.state = kind;
    if (remaining !== undefined) text('latency-remaining', remaining + ' / ' + packetGoal);
  }
  const diagnosticControls = { search, sort, lossOnly, rows, body, empty: document.getElementById('latency-empty') };
  function renderTable(results, completed, controls) {
    const { search, sort, rows, body } = controls;
    const summary = summarize(results);
    const clients = selectClients(summary.clients, search.value, !!controls.lossOnly?.checked, sort.value);
    const displayed = results.length ? [{ ...results[0], stats: summary.ap, delta: summary.ap.mean === null ? null : 0, reference: true }, ...clients] : [];
    const clientKeys = new Map(summary.clients.map((client, index) => [client, String(index + 1)]));
    const currentKeys = new Set();
    let previous = null;
    for (const result of displayed) {
      const key = result.reference ? 'ap' : clientKeys.get(result);
      currentKeys.add(key);
      let row = rows.get(key);
      if (!row) {
        row = document.createElement('tr');
        for (let i = 0; i < 9; i++) row.append(document.createElement('td'));
        rows.set(key, row);
      }
      row.classList.toggle('latency-reference', !!result.reference);
      const stats = result.stats;
      const badge = classify(result, completed);
      const delta = result.delta === null ? '—' : (result.delta > 0 ? '+' : '') + format(result.delta);
      const values = [result.target.name, result.target.mac || 'Referência', result.target.ip || '—',
        format(stats.mean), format(stats.min), format(stats.max), format(stats.loss, '%'), delta];
      values.forEach((value, index) => setText(row.cells[index], value));
      const cell = row.cells[8];
      if (cell.dataset.status !== badge.kind + badge.label) {
        const span = document.createElement('span');
        span.className = 'latency-badge latency-badge-' + badge.kind;
        // Only static, application-owned SVG paths are inserted here.
        span.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + statusPaths[badge.kind] + '</svg>';
        span.append(document.createTextNode(badge.label));
        cell.replaceChildren(span);
        cell.dataset.status = badge.kind + badge.label;
      }
      cell.title = result.target.warning || `${stats.replies}/${stats.total} respostas/amostras`;
      const expected = previous ? previous.nextElementSibling : body.firstElementChild;
      if (expected !== row) body.insertBefore(row, expected);
      previous = row;
    }
    for (const [key, row] of rows) if (!currentKeys.has(key)) { row.remove(); rows.delete(key); }
    controls.empty.hidden = clients.length > 0;
  }
  function render(results, completed = false) {
    latest = results;
    finished = completed;
    renderTable(results, completed, diagnosticControls);
  }
  for (const control of [search, lossOnly, sort]) control.addEventListener('input', () => render(latest, finished));
  const diagnosticTab = document.getElementById('latency-diagnostic-tab');
  const resultsTab = document.getElementById('latency-results-tab');
  const diagnosticPanel = document.getElementById('latency-diagnostic-panel');
  const resultsPanel = document.getElementById('latency-results-panel');
  const finalControls = {
    search: document.getElementById('latency-final-search'),
    sort: document.getElementById('latency-final-sort'),
    body: document.getElementById('latency-final-results'),
    empty: document.getElementById('latency-final-empty'),
    rows: new Map(),
  };
  let activeTab = 'diagnostic', finalView = null, expiryTimer = null;
  const restartPath = '<path d="M20 7v5h-5M20 12a8 8 0 1 0-2 5"/>';
  const playPath = '<path d="m8 5 11 7-11 7Z"/>';
  function selectTab(tab, focus = false) {
    if (tab === 'results' && resultsTab.disabled) return;
    activeTab = tab;
    const resultsActive = tab === 'results';
    diagnosticPanel.hidden = resultsActive;
    resultsPanel.hidden = !resultsActive;
    state.hidden = resultsActive && finished;
    for (const [button, selected] of [[diagnosticTab, !resultsActive], [resultsTab, resultsActive]]) {
      button.setAttribute('aria-selected', String(selected));
      button.tabIndex = selected ? 0 : -1;
      if (selected && focus) button.focus();
    }
    text('latency-start-label', resultsActive ? 'Executar novamente' : 'Start');
    start.querySelector('svg').innerHTML = resultsActive ? restartPath : playPath;
  }
  diagnosticTab.addEventListener('click', () => selectTab('diagnostic'));
  resultsTab.addEventListener('click', () => selectTab('results'));
  for (const tab of [diagnosticTab, resultsTab]) tab.addEventListener('keydown', event => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    if (resultsTab.disabled || event.key === 'Home') selectTab('diagnostic', true);
    else if (event.key === 'End') selectTab('results', true);
    else selectTab(activeTab === 'results' ? 'diagnostic' : 'results', true);
  });
  function resetResults() {
    clearTimeout(expiryTimer);
    finalView = null;
    resultsTab.disabled = true;
    finalControls.search.value = '';
    finalControls.sort.value = 'delta-desc';
    finalControls.rows.clear();
    finalControls.body.replaceChildren();
    selectTab('diagnostic');
  }
  function renderFinal(view) {
    const summary = summarize(view.results);
    const coverage = coverageOf(summary.clients);
    const signed = value => value === null ? '—' : (value > 0 ? '+' : '') + format(value);
    text('latency-final-identity', `${view.results[0].target.name} · ${new Date(view.endsAt).toLocaleString('pt-BR')} · ${view.packetCount} pacotes por destino`);
    text('latency-final-ap', format(summary.ap.mean));
    text('latency-final-ap-loss', 'Perda do AP: ' + format(summary.ap.loss, '%'));
    text('latency-final-mean', format(summary.mean));
    text('latency-final-delta', signed(summary.delta));
    text('latency-final-loss', format(summary.loss, '%'));
    text('latency-final-count', `${coverage.total} clientes avaliados`);
    const percent = count => coverage.total ? format(count * 100 / coverage.total, '%') : '—';
    text('latency-final-coverage-text', `${coverage.responding} respondendo (${percent(coverage.responding)}) · ${coverage.noReply} sem resposta (${percent(coverage.noReply)})`);
    text('latency-final-rate', 'Taxa de resposta: ' + percent(coverage.responding));
    const categories = [
      ['responding', 'Com resposta'], ['noReply', 'Sem resposta'],
      ['noIp', 'Sem IP válido'], ['unmeasured', 'Sem medição válida'],
    ];
    const legend = document.getElementById('latency-coverage-legend');
    legend.replaceChildren();
    const bar = document.getElementById('latency-coverage-bar');
    for (const [key, label] of categories) {
      bar.querySelector(`[data-category="${key}"]`).style.width = (coverage.total ? coverage[key] * 100 / coverage.total : 0) + '%';
      if (coverage[key] || key === 'responding' || key === 'noReply') {
        const item = document.createElement('span');
        item.dataset.category = key;
        item.textContent = `${label}: ${coverage[key]}`;
        legend.append(item);
      }
    }
    bar.setAttribute('aria-label', Array.from(legend.children).map(item => item.textContent).join(', '));
    const top = positiveTop(summary.clients);
    const list = document.getElementById('latency-final-top');
    list.replaceChildren();
    for (const [index, client] of top.entries()) {
      const item = document.createElement('li');
      const position = document.createElement('span');
      position.className = 'latency-final-rank';
      position.textContent = String(index + 1);
      const identity = document.createElement('div');
      const name = document.createElement('strong');
      name.textContent = client.target.name;
      const details = document.createElement('p');
      details.textContent = `Média: ${format(client.stats.mean)} · Perda: ${format(client.stats.loss, '%')}`;
      identity.append(name, details);
      const badge = document.createElement('span');
      badge.className = 'latency-badge latency-badge-' + classify(client, true).kind;
      badge.textContent = signed(client.delta);
      item.append(position, identity, badge);
      list.append(item);
    }
    document.getElementById('latency-final-top-empty').hidden = top.length > 0;
    text('latency-final-table-count', String(coverage.total));
    renderTable(view.results, true, finalControls);
  }
  for (const control of [finalControls.search, finalControls.sort]) control.addEventListener('input', () => {
    if (finalView) renderTable(finalView.results, true, finalControls);
  });
  function complete(view, serverTime) {
    if (finalView) return;
    finalView = view;
    renderFinal(view);
    resultsTab.disabled = false;
    selectTab('results');
    resultsTab.focus();
    start.disabled = false;
    packetPicker.disabled = false;
    const current = session;
    expiryTimer = setTimeout(() => {
      if (session !== current) return;
      resetResults();
      render([]);
      execution('Expirado', 'error', packetGoal);
      state.textContent = 'Os resultados expiraram após cinco minutos. Inicie uma nova avaliação.';
    }, Math.max(0, view.endsAt + 300000 - serverTime));
  }
  async function poll() {
    if (!session || !dialog.open) return;
    const current = session, attempt = generation;
    try {
      const view = await request('/' + encodeURIComponent(current.id));
      if (session !== current || attempt !== generation || !dialog.open) return;
      const serverTime = serverTimes.get(view);
      const processed = packetProgress(view);
      const completed = completionConfirmed(view);
      if (view.failed || (completed && !view.results.some(result => result.samples.some(point => Number.isFinite(point.loss))))) {
        render(view.results, false);
        execution('Erro', 'error', processed);
        state.textContent = view.failed ? 'Não foi possível concluir todos os pacotes. Verifique o coletor e tente novamente.'
          : 'O coletor não retornou medições ICMP válidas. Verifique a comunicação com o coletor e tente novamente.';
        start.disabled = false;
        packetPicker.disabled = false;
        return;
      }
      render(view.results, completed);
      state.textContent = completed ? `Avaliação concluída · ${packetGoal} pacotes por destino.`
        : `Em andamento · ${processed} de ${packetGoal} pacotes concluídos em todos os destinos.`;
      execution(completed ? 'Concluído' : 'Em andamento', completed ? 'completed' : 'running', processed);
      if (completed) { complete(view, Number.isFinite(serverTime) ? serverTime : Date.now()); return; }
      timer = setTimeout(poll, 1000);
    } catch (error) {
      if (session !== current || attempt !== generation || !dialog.open) return;
      execution('Erro', 'error');
      state.textContent = error.message;
      if (error.status === 404) { start.disabled = false; packetPicker.disabled = false; return; }
      timer = setTimeout(poll, 3000);
    }
  }
  settings.addEventListener('click', () => {
    menu.hidden = !menu.hidden;
    settings.setAttribute('aria-expanded', String(!menu.hidden));
    if (!menu.hidden) menu.querySelector('button').focus();
  });
  function hideMenu() { menu.hidden = true; settings.setAttribute('aria-expanded', 'false'); }
  document.addEventListener('click', event => { if (!event.target.closest('.ap-settings')) hideMenu(); });
  document.addEventListener('keydown', event => { if (event.key === 'Escape' && !menu.hidden) { hideMenu(); settings.focus(); } });
  document.getElementById('ap-latency-open').addEventListener('click', async () => {
    hideMenu();
    dialog.showModal();
    const attempt = ++generation;
    start.disabled = true;
    state.textContent = 'Carregando clientes e endereços do IXC…';
    rows.clear();
    body.replaceChildren();
    latest = [];
    resetResults();
    packetGoal = Number(packetPicker.value);
    text('latency-packet-target', String(packetGoal));
    execution('Aguardando', 'waiting', 0);
    search.value = '';
    lossOnly.checked = false;
    sort.value = 'delta-desc';
    render([]);
    try {
      const targets = await request();
      if (!dialog.open || attempt !== generation) return;
      render(targets.map(target => ({ target, samples: [] })));
      state.textContent = `${targets.length - 1} clientes conectados. Pronto para iniciar.`;
      start.disabled = false;
    } catch (error) { if (attempt === generation) { execution('Erro', 'error'); state.textContent = error.message; } }
  });
  start.addEventListener('click', async () => {
    if (busy || start.disabled) return;
    busy = true;
    packetGoal = Number(packetPicker.value);
    packetPicker.disabled = true;
    text('latency-packet-target', String(packetGoal));
    resetResults();
    start.disabled = true;
    close.disabled = true;
    generation++;
    render([]);
    execution('Aguardando', 'waiting', 0);
    state.textContent = 'Preparando medição…';
    try {
      clearTimeout(timer);
      if (session) { await request('/' + encodeURIComponent(session.id), 'DELETE'); session = null; }
      session = await request('?packets=' + packetGoal, 'POST');
      execution('Em andamento', 'running', 0);
      render(session.results);
      poll();
    } catch (error) { execution('Erro', 'error'); state.textContent = error.message; start.disabled = false; packetPicker.disabled = false; }
    finally { busy = false; close.disabled = false; }
  });
  async function dismiss(event) {
    event?.preventDefault();
    if (busy) return;
    busy = true;
    close.disabled = true;
    generation++;
    clearTimeout(timer);
    clearTimeout(expiryTimer);
    try {
      if (session) await request('/' + encodeURIComponent(session.id), 'DELETE');
      session = null;
      resetResults();
      execution('Cancelado', 'cancelled');
      generation++;
      dialog.close();
    } catch (error) { execution('Erro', 'error'); state.textContent = error.message; }
    finally { busy = false; close.disabled = false; }
  }
  packetPicker.addEventListener('change', () => {
    if (packetPicker.disabled) return;
    if (!finished) {
      packetGoal = Number(packetPicker.value);
      text('latency-packet-target', String(packetGoal));
      text('latency-remaining', '0 / ' + packetGoal);
    }
  });
  close.addEventListener('click', dismiss);
  dialog.addEventListener('cancel', dismiss);
})();
