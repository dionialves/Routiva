/* Historical counters come from the same published generation as Availability. */
(() => {
  'use strict';
  if (location.pathname.split('/')[3]) return;
  const $ = id => document.getElementById(id);
  const states = { NORMAL: ['Normal', 'normal'], DEGRADADO: ['Atenção', 'warning'], CRITICO: ['Crítico', 'critical'], SEM_DADOS: ['Sem dados', 'neutral'] };
  const severityLabels = ['Não classificada', 'Informação', 'Atenção', 'Média', 'Alta', 'Desastre'];
  let page = 1, sort = 'name', direction = 'asc', version = 0, debounce, controller;
  const number = value => value == null ? '—' : value.toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const format = (value, unit) => value == null ? '—' : number(value) + unit;
  const duration = millis => {
    const seconds = Math.round(millis / 1000);
    return `${Math.floor(seconds / 3600)} h ${Math.floor(seconds % 3600 / 60)} min ${seconds % 60} s`;
  };
  function cell(tr, value, header = false) {
    const td = document.createElement(header ? 'th' : 'td');
    if (header) td.scope = 'row';
    td.textContent = value;
    tr.append(td);
    return td;
  }
  function feedback(message, error = false) {
    $('diagnostic-status').textContent = message;
    $('diagnostic-status').dataset.state = error ? 'error' : 'notice';
    $('diagnostic-retry').hidden = !error;
    $('diagnostic-feedback-icon').hidden = !error;
  }
  function skeleton() {
    const fragment = document.createDocumentFragment();
    for (let i = 0; i < 6; i++) {
      const tr = document.createElement('tr');
      tr.className = 'group-skeleton';
      tr.setAttribute('aria-hidden', 'true');
      for (let j = 0; j < 8; j++) {
        const td = cell(tr, ''), span = document.createElement('span');
        td.append(span);
      }
      fragment.append(tr);
    }
    $('group-rows').replaceChildren(fragment);
  }
  function render(data) {
    const fragment = document.createDocumentFragment();
    const range = data.from && data.to ? `${new Date(data.from).toLocaleString('pt-BR')} – ${new Date(data.to).toLocaleString('pt-BR')}` : 'Sem medições';
    for (const row of data.rows) {
      const tr = document.createElement('tr');
      const name = cell(tr, '', true), link = document.createElement('a');
      link.href = `/diagnostico/grupos/${encodeURIComponent(row.id)}?minutes=${encodeURIComponent($('group-period').value)}`;
      link.textContent = row.name;
      link.setAttribute('aria-label', `Ver diagnóstico de ${row.name}`);
      name.append(link);
      cell(tr, row.hosts).className = 'availability-number';
      for (const key of ['operational', 'technical', 'loss', 'latency']) {
        const td = cell(tr, ''); td.className = 'availability-number';
        const wrap = document.createElement('div'); wrap.className = 'availability-percent';
        const value = row.metrics[key];
        if (['operational', 'technical'].includes(key)) {
          if (value != null) {
            wrap.append(window.AvailabilityView.meter(value, key));
          }
          const label = key === 'operational' ? 'Disponibilidade operacional' : 'Disponibilidade técnica';
          wrap.title = `${label}: ${format(value, '%')}\nPeríodo: ${range}`;
          if (value != null && row.observedMillis > 0) {
            const available = row.observedMillis * Math.max(0, Math.min(100, value)) / 100;
            wrap.title += `\nTempo disponível: ${duration(available)}\nTempo indisponível: ${duration(row.observedMillis - available)}\nCobertura média observada por host (mesmo critério de Disponibilidade).`;
          }
        } else if (key === 'loss' && value != null) {
          const m = row.metrics;
          wrap.title = `Pacotes enviados: ${m.healthy + m.degraded + m.lost}\nPacotes perdidos: ${m.lost}\nPacotes recebidos: ${m.healthy + m.degraded}\nPerda consolidada: ${format(value, '%')}\nFalhas internas excluídas: ${m.errors}`;
        } else if (value != null) {
          wrap.title = `Latência média: ${format(value, ' ms')}\nMedições válidas: ${row.metrics.healthy + row.metrics.degraded}`;
        }
        const text = document.createElement('span'); text.textContent = format(value, key === 'latency' ? ' ms' : '%');
        wrap.append(text); td.append(wrap);
      }
      const incident = cell(tr, '');
      if (row.incidents > 0) {
        const badges = document.createElement('div');
        badges.className = 'diagnostic-incidents';
        for (let severity = 5; severity >= 0; severity--) {
          const count = row.severities[severity];
          if (!count) continue;
          const badge = document.createElement('a');
          badge.href = `/incidentes?groupId=${encodeURIComponent(row.id)}&severity=${severity}`;
          badge.className = `diagnostic-badge incident-severity-${severity}`;
          badge.textContent = count;
          badge.title = `${severityLabels[severity]}: ${count} ${count === 1 ? 'incidente' : 'incidentes'}`;
          badge.setAttribute('aria-label', badge.title);
          badges.append(badge);
        }
        incident.append(badges);
      } else {
        const badge = document.createElement('a');
        badge.href = `/incidentes?groupId=${encodeURIComponent(row.id)}`;
        badge.className = 'diagnostic-badge neutral';
        badge.textContent = row.incidents ?? '—';
        badge.title = row.incidents == null ? 'Incidentes indisponíveis ou incompletos' : 'Nenhum incidente ativo';
        incident.append(badge);
      }
      const state = cell(tr, ''), status = document.createElement('span');
      const [label, color] = states[row.status] || states.SEM_DADOS;
      status.className = `host-status ${color}`; status.textContent = label;
      status.title = 'Prioridade: incidente crítico ativo, condição histórica crítica, incidente de atenção ativo, condição histórica de atenção, ausência de medições, normal.';
      state.append(status);
      fragment.append(tr);
    }
    $('group-rows').replaceChildren(fragment);
    page = data.number;
    $('groups-empty').hidden = data.total !== 0;
    $('groups-empty').textContent = 'Nenhum grupo encontrado para os filtros selecionados.';
    $('groups-page-status').textContent = `Exibindo ${data.total ? (page - 1) * 20 + 1 : 0}–${Math.min(page * 20, data.total)} de ${data.total} grupos`;
    $('groups-previous').disabled = page <= 1;
    $('groups-next').disabled = page >= data.pages;
    feedback(data.warning || '');
  }
  async function load(initial = false) {
    const request = ++version;
    controller?.abort();
    const requestController = new AbortController(); controller = requestController;
    const timeout = setTimeout(() => requestController.abort(), 15000);
    $('diagnostic-groups').setAttribute('aria-busy', 'true');
    $('groups-refresh').classList.add('refreshing');
    feedback('');
    if (initial) skeleton();
    const params = new URLSearchParams({ minutes: $('group-period').value || '1440', q: $('group-search').value.trim(), status: $('group-status').value, sort, direction, page });
    try {
      const response = await fetch(`/api/diagnostics/group-indicators?${params}`, { cache: 'no-store', signal: controller.signal });
      if (!response.ok) throw new Error('Não foi possível atualizar os grupos. Tente novamente.');
      const data = await response.json();
      if (request === version) render(data);
    } catch (error) {
      if (request === version) {
        if (initial) $('group-rows').replaceChildren();
        feedback(error.name === 'AbortError' ? 'A consulta demorou demais. Tente novamente.' : error.message, true);
      }
    } finally {
      clearTimeout(timeout);
      if (request === version) {
        $('diagnostic-groups').setAttribute('aria-busy', 'false');
        $('groups-refresh').classList.remove('refreshing');
      }
    }
  }
  const reset = () => { page = 1; load(); };
  $('group-search').addEventListener('input', () => {
    clearTimeout(debounce);
    // Invalidate in-flight responses immediately while waiting for the debounce.
    version++; controller?.abort();
    debounce = setTimeout(reset, 300);
  });
  $('group-status').addEventListener('change', reset);
  $('group-period').addEventListener('change', reset);
  $('groups-refresh').addEventListener('click', () => load());
  $('diagnostic-retry').addEventListener('click', () => load());
  $('groups-previous').addEventListener('click', () => { page--; load(); });
  $('groups-next').addEventListener('click', () => { page++; load(); });
  document.querySelectorAll('[data-group-sort]').forEach(button => button.addEventListener('click', () => {
    const key = button.dataset.groupSort;
    direction = sort === key && direction === 'asc' ? 'desc' : 'asc'; sort = key;
    document.querySelectorAll('[data-group-sort]').forEach(item => item.closest('th').setAttribute('aria-sort', item === button ? direction === 'asc' ? 'ascending' : 'descending' : 'none'));
    reset();
  }));
  window.addEventListener('pagehide', () => { version++; controller?.abort(); clearTimeout(debounce); });
  window.addEventListener('pageshow', event => { if (event.persisted) load(); });
  load(true);
})();
