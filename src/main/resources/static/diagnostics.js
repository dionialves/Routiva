/* Group host inventory and on-demand live ping. */
(() => {
  'use strict';
  const collator = new Intl.Collator('pt-BR', { numeric: true, sensitivity: 'base' });
  function compareHostRows(a, b, key = 'name', direction = 1) {
    const left = a[key], right = b[key];
    const missing = value => value == null || value === '' || (typeof value === 'number' && !Number.isFinite(value));
    if (missing(left) !== missing(right)) return missing(left) ? 1 : -1;
    const order = missing(left) ? 0 : typeof left === 'number' && typeof right === 'number'
      ? left - right : collator.compare(String(left), String(right));
    return direction * order || collator.compare(a.name, b.name) || collator.compare(a.id, b.id);
  }
  if (typeof module !== 'undefined' && module.exports) module.exports = { compareHostRows };
  if (typeof document === 'undefined') return;
  if (!location.pathname.split('/')[3]) return;
  const $ = id => document.getElementById(id);
  const format = window.AvailabilityView.format;
  const groupId = decodeURIComponent(location.pathname.split('/')[3] || '');
  let hosts = [], busy = false, stopped = false;
  const cells = new Map(), tableRows = new Map();
  const sortKeys = ['name', 'ip', 'model', 'version', 'incidents', 'ping', 'loss'];
  let sortKey = 'name', sortDirection = 1;
  function sortRows() {
    const fragment = document.createDocumentFragment();
    const rows = [...tableRows.values()].sort((a, b) => compareHostRows(a.values, b.values, sortKey, sortDirection));
    for (const row of rows) fragment.append(row.element);
    $('diagnostic-hosts').replaceChildren(fragment);
    for (const key of sortKeys) {
      const button = $('diagnostic-sort-' + key);
      button.closest('th').setAttribute('aria-sort', key === sortKey ? sortDirection === 1 ? 'ascending' : 'descending' : 'none');
    }
  }
  for (const key of sortKeys) {
    $('diagnostic-sort-' + key).addEventListener('click', () => {
      sortDirection = sortKey === key ? -sortDirection : 1;
      sortKey = key;
      sortRows();
    });
  }
  let liveTimer, liveBusy = false, pollGeneration = 0;
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
    cells.clear(); tableRows.clear();
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
      textCell(tr, equipment.model || 'Não informado');
      const version = textCell(tr, equipment.version || 'Não informado');
      version.title = version.textContent;
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
      tableRows.set(host.id, { element: tr, values: { id: host.id, name: host.name, ip: host.ip,
        model: equipment.model, version: equipment.version, incidents: incident, ping: null, loss: null } });
    }
    sortRows();
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
      const values = tableRows.get(row.id).values;
      values.ping = fresh ? row.ping : null; values.loss = fresh ? row.loss : null;
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
    if (['ping', 'loss'].includes(sortKey)) sortRows();
  }
  async function poll() {
    const generation = pollGeneration;
    if (stopped || !pingRunning) return;
    if (!document.hidden && !busy && !liveBusy) {
      liveBusy = true;
      globalThis.TableState?.loading('diagnostic-hosts');
      try {
        await live();
        if (pingRunning && generation === pollGeneration) globalThis.TableState?.done('diagnostic-hosts');
      } catch (error) {
        if (pingRunning && generation === pollGeneration) globalThis.TableState?.error('diagnostic-hosts', error.message);
      }
      finally { liveBusy = false; }
    }
    if (!stopped && pingRunning && generation === pollGeneration) liveTimer = setTimeout(poll, 2000);
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
    if (!pingRunning) globalThis.TableState?.done('diagnostic-hosts');
    if (pingRunning) {
      liveSince = Date.now();
      for (const [id, target] of cells) {
        const values = tableRows.get(id).values; values.ping = null; values.loss = null;
        badge(target[0], 'Aguardando'); badge(target[1], 'Aguardando');
        target[2].dataset.state = 'unknown';
        target[2].setAttribute('aria-label', 'Aguardando medições');
        target[2].title = 'Aguardando medições';
      }
      if (['ping', 'loss'].includes(sortKey)) sortRows();
      poll();
    }
  });
  $('diagnostic-retry').addEventListener('click', () => start().catch(e => feedback(e.message, true)));
  window.addEventListener('pagehide', () => { stopped=true; pollGeneration++; clearTimeout(liveTimer); });
  window.addEventListener('pageshow', event => { if (event.persisted && groupId) { stopped=false; poll(); } });
  async function start() {
    const groups = await get('/api/diagnostics/groups');
    const group = groups.find(g => g.id===groupId);
    if (!group) throw new Error('Grupo não encontrado no inventário monitorado.');
    $('diagnostic-title').textContent=group.name; $('diagnostic-description').textContent='Hosts do grupo e comunicação em tempo real.';
    $('group-details').hidden=false; $('diagnostic-groups').hidden=true;
    busy = true;
    $('group-details').classList.toggle('initial-loading', true);
    try {
      globalThis.TableState?.loading('diagnostic-hosts');
      await details();
      globalThis.TableState?.done('diagnostic-hosts');
    } catch (error) {
      globalThis.TableState?.error('diagnostic-hosts', error.message);
      feedback(error.message, true);
    } finally {
      busy = false;
      $('group-details').classList.remove('initial-loading');
    }
  }
  start().catch(error => feedback(error.message, true));
})();
