/* Group diagnostics shares the availability report, cards and chart renderer. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const format = window.AvailabilityView.format;
  const groupId = decodeURIComponent(location.pathname.split('/')[3] || '');
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
      const name = document.createElement('span'); name.textContent = host.name;
      nameCell.append(dot, name); textCell(tr, host.ip || 'Sem IP');
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
          const item = document.createElement('span');
          item.className = `diagnostic-badge incident-severity-${severity}`;
          item.textContent = count;
          item.title = `${labels[severity]}: ${count} ${count === 1 ? 'incidente' : 'incidentes'}`;
          item.setAttribute('aria-label', item.title);
          badges.append(item);
        }
        incidentCell.append(badges);
      } else badge(incidentCell, incident ?? 'Indisponível');
      const ping = textCell(tr, ''), loss = textCell(tr, '');
      badge(ping, 'Aguardando'); badge(loss, 'Aguardando');
      cells.set(host.id, [ping, loss, dot]);
      fragment.append(tr);
    }
    $('diagnostic-hosts').replaceChildren(fragment);
    $('diagnostic-hosts-empty').hidden = hosts.length !== 0;
    const known = hosts.filter(h => Number.isFinite(data.incidents[h.id]));
    const incidents = known.reduce((sum, h) => sum + data.incidents[h.id], 0);
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
  class MetricChart {
    constructor(container, key, unit, color) {
      Object.assign(this, { container, key, unit, color });
      this.index = 0;
      if (typeof ResizeObserver !== 'undefined') {
        this.observer = new ResizeObserver(() => { if (this.report) this.draw(); });
        this.observer.observe(container);
      }
      secondaryCharts.push(this);
    }
    update(value) { this.report = value; this.draw(); }
    clear(message = 'Sem medições no período.') {
      this.report = null; chartEmpty(this.container, message);
    }
    draw() {
      const report = this.report, { container, key, unit, color } = this;
      if (!report?.series.some(p => p.metrics[key] != null)) { this.clear(); return; }
      container.replaceChildren();
      const ns = 'http://www.w3.org/2000/svg';
      const svg = document.createElementNS(ns, 'svg');
      const width = Math.max(300, container.getBoundingClientRect().width), height = 230;
      svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
      svg.setAttribute('role', 'group'); svg.setAttribute('tabindex', '0');
      svg.setAttribute('aria-label', `${key === 'loss' ? 'Perda de pacotes' : 'Latência média'}. Use as setas para consultar os intervalos.`);
      const node = (tag, attrs, text) => {
        const el = document.createElementNS(ns, tag);
        Object.entries(attrs).forEach(([k, v]) => el.setAttribute(k, v));
        if (text) el.textContent = text; svg.append(el); return el;
      };
      const max = key === 'loss' ? 100 : Math.max(1, ...report.series.map(p => p.metrics[key] || 0)) * 1.1;
      const left = 65, right = width - 16, bottom = 186;
      const x = at => left + (at - report.from) / Math.max(1, report.to - report.from) * (right - left);
      const y = value => bottom - value / max * 162;
      for (const ratio of [0, .5, 1]) {
        node('line', { x1:left, x2:right, y1:y(max * ratio), y2:y(max * ratio), class:'availability-gridline' });
        node('text', { x:left - 8, y:y(max * ratio)+4, 'text-anchor':'end', class:'availability-axis' }, (max * ratio).toFixed(1) + unit);
      }
      const points = report.series.map(p => ({ ...p, [key]:p.metrics[key] }));
      node('path', { d:window.AvailabilityChart.paths(points,key,x,y,bottom).line, fill:'none', stroke:color, 'stroke-width':2 });
      const date = at => new Date(at).toLocaleString('pt-BR', {timeZone:'America/Sao_Paulo',day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit'});
      for (const [at, anchor] of [[report.from, 'start'], [report.to, 'end']]) {
        node('line', { x1:x(at), x2:x(at), y1:24, y2:bottom, class:'availability-gridline' });
        node('text', {x:x(at), y:218, 'text-anchor':anchor, class:'availability-axis'}, date(at));
      }
      const cursor = node('line', {x1:0,x2:0,y1:24,y2:bottom,stroke:color,visibility:'hidden','stroke-dasharray':'3 4'});
      const dot = node('circle', {cx:0,cy:0,r:3,fill:color,visibility:'hidden'});
      const tooltip = document.createElement('div'); tooltip.className = 'diagnostic-chart-tooltip'; tooltip.hidden = true;
      tooltip.setAttribute('role','status');
      const show = index => {
        this.index = Math.max(0, Math.min(points.length - 1, index));
        const point = points[this.index], px = x((point.from + point.to) / 2);
        cursor.setAttribute('x1',px); cursor.setAttribute('x2',px); cursor.setAttribute('visibility','visible');
        dot.setAttribute('visibility',point[key] == null ? 'hidden' : 'visible');
        dot.setAttribute('cx',px); dot.setAttribute('cy',y(point[key] || 0));
        tooltip.textContent = `${date(point.from)} — ${date(point.to)} · ${format(point[key], unit)}`;
        tooltip.hidden = false;
        tooltip.style.left = Math.max(8,Math.min(px,width - Math.min(280,width - 16))) + 'px';
      };
      const hide = () => { tooltip.hidden = true; cursor.setAttribute('visibility','hidden'); dot.setAttribute('visibility','hidden'); };
      svg.addEventListener('pointermove', event => {
        const bounds = svg.getBoundingClientRect();
        const at = report.from + ((event.clientX - bounds.left) * width / bounds.width - left) / (right - left) * (report.to - report.from);
        const index = points.findIndex(p => p.to > at);
        show(index < 0 ? points.length - 1 : index);
      });
      svg.addEventListener('pointerleave',hide);
      svg.addEventListener('focus', () => show(this.index));
      svg.addEventListener('blur',hide);
      svg.addEventListener('keydown', event => {
        if (event.key === 'Escape') hide();
        if (['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) {
          event.preventDefault(); show(event.key === 'Home' ? 0 : event.key === 'End' ? points.length - 1 : this.index + (event.key === 'ArrowRight' ? 1 : -1));
        }
      });
      container.append(svg,tooltip);
    }
  }
  const latencyChart = new MetricChart($('latency-chart'), 'latency', ' ms', '#06b6d4');
  const lossChart = new MetricChart($('loss-chart'), 'loss', '%', '#f59e0b');
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
    latencyChart.update(report); lossChart.update(report);
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
  $('period').addEventListener('change', () => history().catch(e => feedback(e.message, true)));
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
    if (!groupId) {
      $('diagnostic-status').textContent = groups.length ? '' : 'Nenhum grupo monitorado. Importe os hosts na integração Zabbix.';
      let page = 1;
      const pageSize = 50;
      const normalize = value => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('pt-BR');
      function renderGroups() {
        const query = normalize($('group-search').value.trim());
        const filtered = groups.filter(group => normalize(group.name).includes(query));
        const pages = Math.max(1, Math.ceil(filtered.length / pageSize));
        page = Math.max(1, Math.min(page, pages));
        const offset = (page - 1) * pageSize;
        const fragment = document.createDocumentFragment();
        for (const group of filtered.slice(offset, offset + pageSize)) {
          const tr = document.createElement('tr');
          const name = textCell(tr, '', true);
          const link = document.createElement('a');
          link.href = `/diagnostico/grupos/${encodeURIComponent(group.id)}`;
          link.textContent = group.name;
          name.append(link);
          textCell(tr, group.hosts).className = 'availability-number';
          const action = textCell(tr, '');
          const open = document.createElement('a');
          open.href = link.href;
          open.textContent = 'Ver diagnóstico →';
          open.setAttribute('aria-label', `Ver diagnóstico de ${group.name}`);
          action.append(open);
          fragment.append(tr);
        }
        $('group-rows').replaceChildren(fragment);
        $('group-count').textContent = `${filtered.length} de ${groups.length} grupos`;
        $('groups-empty').hidden = filtered.length !== 0;
        $('groups-empty').textContent = groups.length
          ? 'Nenhum grupo corresponde à pesquisa.'
          : 'Nenhum grupo monitorado. Importe os hosts na integração Zabbix.';
        $('groups-page-status').textContent = filtered.length
          ? `${offset + 1}–${Math.min(offset + pageSize, filtered.length)} de ${filtered.length} · Página ${page} de ${pages}`
          : 'Página 1 de 1';
        $('groups-previous').disabled = page <= 1;
        $('groups-next').disabled = page >= pages;
      }
      $('group-search').addEventListener('input', () => { page = 1; renderGroups(); });
      $('groups-previous').addEventListener('click', () => { page--; renderGroups(); });
      $('groups-next').addEventListener('click', () => { page++; renderGroups(); });
      renderGroups();
      return;
    }
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
