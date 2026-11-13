/* Group diagnostics shares the availability report, cards and chart renderer. */
(() => {
  'use strict';
  if (!location.pathname.split('/')[3]) return;
  const $ = id => document.getElementById(id);
  const format = window.AvailabilityView.format;
  const groupId = decodeURIComponent(location.pathname.split('/')[3] || '');
  const selectedPeriod = location.search ? new URLSearchParams(location.search).get('minutes') : null;
  const periodStorageKey = 'routiva.diagnostics.period';
  const periods = Array.from($('period').options || [], option => option.value);
  let savedPeriod;
  try { savedPeriod = localStorage.getItem(periodStorageKey); } catch {}
  $('period').value = periods.includes(selectedPeriod) ? selectedPeriod
    : periods.includes(savedPeriod) ? savedPeriod : '1440';
  function savePeriod() {
    try { localStorage.setItem(periodStorageKey, $('period').value); } catch {}
  }
  savePeriod();
  let hosts = [], report, busy = false, stopped = false, historyVersion = 0;
  const cells = new Map();
  let refreshTimer, liveTimer, liveBusy = false, pollGeneration = 0;
  let limits = {};
  let pingRunning = false;
  let liveSince = Date.now();
  function badge(cell, value, state = 'neutral') {
    cell.replaceChildren();
    const span = document.createElement('span');
    span.className = `diagnostic-badge ${state}`;
    span.textContent = value;
    cell.append(span);
  }
  function feedback(message, error = false) {
    $('diagnostic-status').textContent = message;
    $('diagnostic-status').dataset.state = error ? 'error' : 'notice';
    $('diagnostic-retry').hidden = !error;
    $('diagnostic-feedback-icon').hidden = !error;
  }
  function unknownLive() {
    for (const target of cells.values()) {
      badge(target[0], 'Indisponível'); badge(target[1], 'Indisponível');
      target[2].dataset.state = 'unknown';
      target[2].title = 'Falha na atualização das medições';
      target[2].setAttribute('aria-label', 'Estado desconhecido: falha na atualização');
    }
  }
  const chart = new window.AvailabilityChart.Chart($('availability-chart'), $('chart-granularity'));
  chart.height = 260;
  async function get(url) {
    let response;
    try { response = await fetch(url, { cache: 'no-store', signal: AbortSignal.timeout(15000) }); }
    catch { throw new Error('Não foi possível conectar ao servidor. Tente novamente.'); }
    if (!response.ok) throw new Error(response.status === 404 ? 'Grupo não encontrado no inventário monitorado.' : 'Não foi possível atualizar os dados. Tente recarregar.');
    return response.json();
  }
  function textCell(tr, text, header = false) {
    const cell = document.createElement(header ? 'th' : 'td');
    cell.textContent = text ?? 'Indisponível';
    if (header) cell.scope = 'row';
    tr.append(cell); return cell;
  }
  async function details() {
    const data = await get(`/api/diagnostics/groups/${encodeURIComponent(groupId)}/details`);
    hosts = data.hosts;
    limits = data.limits || {};
    const fragment = document.createDocumentFragment(); cells.clear();
    for (const host of hosts) {
      const tr = document.createElement('tr'), equipment = data.equipment[host.id] || {};
      const nameCell = textCell(tr, '', true);
      const dot = document.createElement('span'); dot.className = 'host-connectivity';
      dot.dataset.state = 'unknown'; dot.setAttribute('role', 'img');
      dot.setAttribute('aria-label', 'Estado desconhecido');
      const name = document.createElement('a'); name.textContent = host.name;
      name.className = 'availability-host-name';
      nameCell.append(dot, name);
      window.HostMenu.attach(name, nameCell, { ip: host.ip, hostId: host.id });
      textCell(tr, host.ip || 'Sem IP');
      textCell(tr, equipment.model || 'Não informado'); textCell(tr, equipment.version || 'Não informado');
      const incident = data.incidents[host.id];
      const incidentCell = textCell(tr, '');
      const severities = data.incidentSeverities?.[host.id];
      if (incident > 0 && severities) {
        const badges = document.createElement('div');
        badges.className = 'diagnostic-incidents';
        const labels = ['Não classificado', 'Informação', 'Atenção', 'Média', 'Alta', 'Desastre'];
        for (let severity = 5; severity >= 0; severity--) {
          const count = severities[severity];
          if (!count) continue;
          const item = document.createElement('a');
          item.href = `/incidentes?groupId=${encodeURIComponent(groupId)}&hostId=${encodeURIComponent(host.id)}&severity=${severity}`;
          item.className = `diagnostic-badge incident-severity-${severity}`;
          item.textContent = count;
          item.title = `${labels[severity]}: ${count} ${count === 1 ? 'incidente' : 'incidentes'}`;
          item.setAttribute('aria-label', item.title);
          badges.append(item);
        }
        incidentCell.append(badges);
      } else {
        const link = document.createElement('a');
        link.href = `/incidentes?groupId=${encodeURIComponent(groupId)}&hostId=${encodeURIComponent(host.id)}`;
        link.className = 'diagnostic-badge neutral';
        link.textContent = incident ?? 'Indisponível';
        incidentCell.append(link);
      }
      const ping = textCell(tr, ''), loss = textCell(tr, '');
      badge(ping, 'Aguardando'); badge(loss, 'Aguardando');
      cells.set(host.id, [ping, loss, dot]);
      fragment.append(tr);
    }
    $('diagnostic-hosts').replaceChildren(fragment);
    $('diagnostic-hosts-empty').hidden = hosts.length !== 0;
    const known = hosts.filter(h => Number.isFinite(data.incidents[h.id]));
    const incidents = known.reduce((sum, h) => sum + data.incidents[h.id], 0);
    $('incident-count').href = `/incidentes?groupId=${encodeURIComponent(groupId)}`;
    $('incident-count').textContent = known.length === hosts.length
      ? `${incidents} incidentes ativos` : known.length ? `${incidents} incidentes · dados parciais` : 'Incidentes indisponíveis';
    feedback(data.warning, Boolean(data.warning));
  }
  async function live() {
    const since = liveSince, generation = pollGeneration;
    const data = await get(`/api/diagnostics/groups/${encodeURIComponent(groupId)}/live?since=${since}`);
    if (!pingRunning || stopped || generation !== pollGeneration || since !== liveSince) return;
    for (const row of data) {
      const target = cells.get(row.id); if (!target) continue;
      const fresh = ['Respondendo', 'Sem resposta'].includes(row.state);
      const pingState = row.ping == null || !fresh ? 'neutral'
        : Number.isFinite(limits.latency) ? row.ping <= limits.latency ? 'success' : 'warning' : 'neutral';
      const lossState = row.loss == null || !fresh ? 'neutral'
        : Number.isFinite(limits.lossCritical) && row.loss >= limits.lossCritical ? 'critical'
        : Number.isFinite(limits.lossNormal) ? row.loss > limits.lossNormal ? 'warning' : 'success' : 'neutral';
      badge(target[0], row.ping == null ? row.state === 'Sem resposta' ? 'Sem resposta' : 'Sem dados' : format(row.ping, ' ms'), pingState);
      badge(target[1], row.loss == null ? 'Sem dados' : format(row.loss), lossState);
      target[2].dataset.state = row.state === 'Respondendo' ? 'up' : row.state === 'Sem resposta' ? 'down' : 'unknown';
      const measured = row.measuredAt ? ` · ${new Date(row.measuredAt).toLocaleString('pt-BR')}` : '';
      target[2].title = row.state + measured;
      target[2].setAttribute('aria-label', row.state + measured);
    }
  }
  function chartEmpty(container, message) {
    container.replaceChildren();
    const empty = document.createElement('p'); empty.className = 'diagnostic-chart-empty';
    empty.append(window.AppIcon('chart-line'), document.createTextNode(message));
    container.append(empty);
  }
  const secondaryCharts = [];
  const latencyChart = new window.PerformanceChart($('latency-chart'), 'latency', ' ms', '#06b6d4');
  secondaryCharts.push(latencyChart);
  async function history() {
    const version = ++historyVersion;
    if (report && report.minutes !== $('period').value) {
      chart.clear();
      for (const key of ['operational', 'technical', 'loss', 'latency']) $(key).textContent = '—';
      secondaryCharts.forEach(chart => chart.clear('Carregando período…'));
      window.AvailabilityView.times(null);
      $('diagnostic-range').textContent = 'Carregando período…';
    }
    const data = await get(`/api/availability?minutes=${$('period').value}&groupId=${encodeURIComponent(groupId)}`);
    if (version !== historyVersion) return;
    report = data.view?.report;
    if (report) report.minutes = $('period').value;
    if (!report || report.evaluatedAt == null) {
      chart.clear(); chartEmpty($('availability-chart'), 'Histórico em preparação…');
      for (const key of ['operational','technical','loss','latency']) $(key).textContent = 'Sem dados';
      window.AvailabilityView.times(null);
      secondaryCharts.forEach(chart => chart.clear('Histórico em preparação…'));
      $('diagnostic-range').textContent = 'Histórico em preparação…'; return;
    }
    for (const key of ['operational','technical','loss','latency']) $(key).textContent = format(report.total[key], key === 'latency' ? ' ms' : '%');
    window.AvailabilityView.times(report); chart.update(report);
    $('diagnostic-range').textContent = `${new Date(report.from).toLocaleString('pt-BR')} — ${new Date(report.to).toLocaleString('pt-BR')} · ${report.grouping || ''}`;
    latencyChart.update(report);
    if (!report.series.some(p => p.metrics.technical != null || p.metrics.operational != null)) {
      chart.clear(); chartEmpty($('availability-chart'), 'Sem medições no período.');
    }
  }
  async function poll() {
    const generation = pollGeneration;
    if (stopped || !pingRunning) return;
    if (!document.hidden && !busy && !liveBusy) {
      liveBusy = true;
      try { await live(); }
      catch (error) { if (pingRunning && generation === pollGeneration) unknownLive(); }
      finally { liveBusy = false; }
    }
    if (!stopped && pingRunning && generation === pollGeneration) liveTimer = setTimeout(poll, 2000);
  }
  function scheduleRefresh() {
    clearTimeout(refreshTimer);
    if (stopped) return;
    refreshTimer = setTimeout(async () => {
      if (!document.hidden && !busy) {
        try { await history(); }
        catch (error) { feedback(error.message, true); }
      }
      scheduleRefresh();
    }, 10000);
  }
  $('diagnostic-ping').addEventListener('click', () => {
    pingRunning = !pingRunning;
    pollGeneration++;
    clearTimeout(liveTimer);
    const button = $('diagnostic-ping');
    const label = pingRunning ? 'Parar ping' : 'Iniciar ping';
    button.replaceChildren(window.AppIcon(pingRunning ? 'stop' : 'play'));
    button.setAttribute('aria-label', label);
    button.setAttribute('aria-pressed', String(pingRunning));
    button.title = label;
    $('auto-summary').textContent = pingRunning ? 'Ping em execução · 2 s' : 'Ping parado';
    if (pingRunning) {
      liveSince = Date.now();
      for (const target of cells.values()) {
        badge(target[0], 'Aguardando'); badge(target[1], 'Aguardando');
        target[2].dataset.state = 'unknown';
        target[2].setAttribute('aria-label', 'Aguardando medições');
        target[2].title = 'Aguardando medições';
      }
      poll();
    }
  });
  $('diagnostic-retry').addEventListener('click', () => {
    if (groupId && !$('group-details').hidden) history().catch(e => feedback(e.message, true));
    else start().catch(e => feedback(e.message, true));
  });
  $('period').addEventListener('change', () => {
    savePeriod();
    const params = new URLSearchParams(location.search);
    params.set('minutes', $('period').value);
    window.history.replaceState(null, '', location.pathname + '?' + params);
    history().catch(e => feedback(e.message, true));
  });
  $('chart-lines').addEventListener('click', () => { chart.mode='lines'; chart.draw(); $('chart-lines').setAttribute('aria-pressed','true'); $('chart-bars').setAttribute('aria-pressed','false'); });
  $('chart-bars').addEventListener('click', () => { chart.mode='bars'; chart.draw(); $('chart-bars').setAttribute('aria-pressed','true'); $('chart-lines').setAttribute('aria-pressed','false'); });
  $('chart-expand').addEventListener('click', () => { chart.expanded=!chart.expanded;
    $('chart-expand').setAttribute('aria-expanded',String(chart.expanded));
    $('chart-expand').setAttribute('aria-label',chart.expanded ? 'Recolher gráfico' : 'Expandir gráfico');
    $('chart-expand').title = chart.expanded ? 'Recolher gráfico' : 'Expandir gráfico';
    $('availability-chart').closest('.chart-card').classList.toggle('chart-expanded',chart.expanded);
    document.body.classList.toggle('chart-is-expanded',chart.expanded); chart.draw(); });
  document.addEventListener('keydown', event => { if (event.key === 'Escape' && chart.expanded) $('chart-expand').click(); });
  window.addEventListener('pagehide', () => { stopped=true; pollGeneration++; clearTimeout(liveTimer); clearTimeout(refreshTimer); });
  window.addEventListener('pageshow', event => { if (event.persisted && groupId) { stopped=false; poll(); scheduleRefresh(); } });
  async function start() {
    const groups = await get('/api/diagnostics/groups');
    const group = groups.find(g => g.id===groupId);
    if (!group) throw new Error('Grupo não encontrado no inventário monitorado.');
    $('diagnostic-title').textContent=group.name; $('diagnostic-description').textContent='Indicadores do grupo e comunicação dos hosts em tempo real.';
    $('group-details').hidden=false; $('diagnostic-groups').hidden=true;
    busy = true;
    $('group-details').classList.toggle('initial-loading', true);
    try {
      await Promise.all([details(), history()]);
    } catch (error) {
      feedback(error.message, true);
    } finally {
      busy = false;
      $('group-details').classList.remove('initial-loading');
    }
    scheduleRefresh();
  }
  start().catch(error => feedback(error.message, true));
})();
