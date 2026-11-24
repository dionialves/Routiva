(() => {
  'use strict';
  const format = (n, unit = '') => n == null ? 'Indisponível' : `${Number(n).toLocaleString('pt-BR', { maximumFractionDigits: 2 })} ${unit}`.trim();
  const date = n => n == null ? '—' : new Date(Number(n)).toLocaleString('pt-BR');
  function overviewFields(client) {
    const c = client.latest, data = c?.data || {}, seen = client.lastSeen || {};
    return [['MAC', c?.mac || seen.mac || '-'], ['Login', client.login || '-'],
      ['Nome do rádio', data['radio-name'] || seen.radioName || '-'],
      ['Grupo', (client.groups || []).map(group => group.name).join(', ') || '-'],
      ['AP', data.apName || seen.host_name || seen.host_id || '-'],
      ['Interface', data.interface || seen.interface || '-'], ['IP (última coleta)', data['ip-address'] || '-'],
      ['Última coleta', c ? date(c.observed_to) : '-'], ['Tempo conectado', data.uptime || '-'],
      ['Plano / Perfil', '-']];
  }
  const historyMetric = (n, unit) => n == null ? '—' : format(n, unit);
  function historyValues(c) {
    const v = tableValues({ latest:c });
    return [c.mac || '—', date(c.observed_to), c.data?.apName || c.host_id || '—', historyMetric(v.signal, 'dBm'),
      v.statusLabel === '-' ? '—' : v.statusLabel, historyMetric(v.snr, 'dB'), historyMetric(v.ccq, '%'), historyMetric(v.latency, 'ms'), historyMetric(v.loss, '%')];
  }
  function collectionFields(c) {
    const d = c.data || {}, v = tableValues({ latest:c }), test = c.latency;
    const detailMetric = (n, unit) => n == null ? '-' : format(n, unit);
    const values = [['AP', d.apName || c.host_id || '-'], ['Data da coleta', date(c.observed_to)],
      ['Início da observação', date(c.observed_from)], ['Fim da observação', date(c.observed_to)],
      ['MAC', c.mac || '-'], ['IP', d['ip-address'] || '-'], ['Interface', d.interface || '-'],
      ['Login observado', d.observedLogin || '-'], ['Nome do rádio', d['radio-name'] || '-'],
      ['Tempo conectado', d.uptime || '-'], ['Distância', d.distance == null ? '-' : `${d.distance} km`],
      ['Sinal', detailMetric(v.signal, 'dBm')], ['Conexão', v.statusLabel], ['SNR', detailMetric(v.snr, 'dB')], ['TX CCQ', detailMetric(v.ccq, '%')], ['Latência', detailMetric(v.latency, 'ms')], ['Perda de pacote', detailMetric(v.loss, '%')]];
    for (const [key, label, unit] of [['p-throughput','P Throughput','Mbps'], ['tx-rate','TX','Mbps'], ['rx-rate','RX','Mbps']]) {
      const n = d.metrics?.[key]?.mean; values.push([label, n == null ? '-' : format(n, unit)]);
    }
    for (const [key, label] of [['signal','Sinal'], ['signal-to-noise','SNR'], ['tx-ccq','TX CCQ'], ['p-throughput','P Throughput'], ['tx-rate','TX'], ['rx-rate','RX']])
      values.push([`Amostras de ${label}`, d.metrics?.[key]?.count ?? '-']);
    values.push(['Total de leituras', d.sampleCount ?? '-'], ['Motivo da conexão', d.connectionDiagnosis?.reason || '-'],
      ['Resumo do diagnóstico', d.connectionDiagnosis?.summary || '-'], ['Horário do diagnóstico', date(d.connectionDiagnosis?.at)],
      ['Horário do teste', test ? date(test.tested_at) : '-'], ['IP testado', test?.data.ip || '-'],
      ['Respostas', test?.data.replies ?? '-'], ['Pacotes perdidos', test?.data.lost ?? '-'], ['Resultados ICMP', test?.data.total ?? '-']);
    return values;
  }
  function observationPeriod(collection) {
    const start = collection.observed_from, end = collection.observed_to;
    if (start == null || end == null) return '—';
    const first = new Date(Number(start)), last = new Date(Number(end));
    const sameDay = first.toDateString() === last.toDateString();
    return sameDay ? `${first.toLocaleTimeString('pt-BR')} – ${last.toLocaleTimeString('pt-BR')}` : `${date(start)} – ${date(end)}`;
  }
  function icmpDetails(collection) {
    if (collection.latency == null) return { message:'Teste ICMP não realizado nesta coleta.', fields:[], unavailable:[] };
    const test = collection.latency, data = test.data || {};
    const candidates = [['Horário do teste', test.tested_at, value => date(value)], ['IP testado', data.ip],
      ['Latência', data.latency, value => format(value, 'ms')], ['Perda de pacote', data.loss, value => format(value, '%')],
      ['Respostas', data.replies], ['Pacotes perdidos', data.lost], ['Resultados ICMP', data.total]];
    const present = ([, value]) => value != null && value !== '';
    return { message:'', fields:candidates.filter(present).map(([label, value, formatter]) => [label, formatter ? formatter(value) : value]),
      unavailable:candidates.filter(entry => !present(entry)).map(([label]) => label) };
  }
  function paginateClients(rows, requestedPage) {
    const pageSize = 50, total = rows.length;
    const page = Math.max(1, Math.min(requestedPage, Math.max(1, Math.ceil(total / pageSize))));
    return { page, total, start:total ? (page - 1) * pageSize + 1 : 0, end:Math.min(page * pageSize, total),
      rows:rows.slice((page - 1) * pageSize, page * pageSize), previous:page > 1, next:page * pageSize < total };
  }
  function tableValues(client) {
    const c = client.latest;
    const severity = c?.data.connectionSeverity;
    const status = severity == null ? 'unknown' : severity === 0 ? 'normal' : severity === 3 ? 'critical' : 'attention';
    return { client: client.login || client.provisional_mac || client.lastSeen?.mac || '-',
      ap: c?.data.apName || client.lastSeen?.host_name || client.lastSeen?.host_id || null,
      apId: c?.host_id || client.lastSeen?.host_id || null, observed: c?.observed_to ?? null,
      status, statusLabel: severity == null ? '-' : (c?.data.connectionDiagnosis?.label || ['Normal', 'Atenção', 'Ruim', 'Crítico'][severity] || '-'), signal: c?.data.metrics?.signal?.mean ?? null, snr: c?.data.metrics?.['signal-to-noise']?.mean ?? null,
      ccq: c?.data.metrics?.['tx-ccq']?.mean ?? null, latency: c?.latency?.data.latency ?? null, loss: c?.latency?.data.loss ?? null };
  }
  function compareClients(a, b, key, direction) {
    let left = tableValues(a)[key], right = tableValues(b)[key];
    if (key === 'status') { const order = { normal:0, attention:1, critical:2, unknown:null }; left = order[left]; right = order[right]; }
    if (left == null || right == null) return left == null ? (right == null ? 0 : 1) : -1;
    return direction * (typeof left === 'number' ? left - right : String(left).localeCompare(String(right), 'pt-BR', { numeric:true, sensitivity:'base' }));
  }
  function filterClients(clients, selectedClients, aps, statuses, groups = new Set()) {
    return clients.filter(client => {
      const values = tableValues(client);
      return (!selectedClients.size || selectedClients.has(client.id)) && (!aps.size || aps.has(values.apId))
        && (!statuses.size || statuses.has(values.status))
        && (!groups.size || (client.groups || []).some(group => groups.has(group.id)));
    });
  }
  if (typeof module !== 'undefined') module.exports = { format, tableValues, compareClients, filterClients, overviewFields, historyValues, collectionFields, paginateClients, observationPeriod, icmpDetails };
  if (typeof document === 'undefined') return;
  const $ = id => document.getElementById(id);
  let clients = [], selected, page = 0, generation = 0, trigger, pending;
  async function request(path = '', body) {
    const headers = {};
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content;
    if (token && header) headers[header] = token;
    if (body) headers['Content-Type'] = 'application/json';
    const response = await fetch('/api/clients/radio' + path, { cache: 'no-store', headers, method: body ? 'POST' : 'GET', body: body ? JSON.stringify(body) : undefined });
    const data = await response.json();
    if (!response.ok) throw new Error(data.message || data.detail || 'Não foi possível carregar os dados.');
    if (Array.isArray(data)) {
      const total = response.headers?.get?.('X-Total-Count');
      if (total != null) data.totalCount = Number(total);
    }
    return data;
  }
  async function copyDetails(value, message) {
    const state = $('radio-dialog-state');
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(value);
      } else {
        // The local dashboard can also be served over HTTP.
        const field = document.createElement('textarea');
        const previousFocus = document.activeElement;
        field.className = 'radio-copy-buffer';
        field.readOnly = true;
        field.value = value;
        $('radio-dialog').append(field);
        try {
          field.select();
          if (!document.execCommand('copy')) throw new Error('Clipboard unavailable');
        } finally {
          field.remove();
          previousFocus?.focus({ preventScroll: true });
        }
      }
      state.textContent = message;
      state.classList.add('radio-copy-success');
    } catch {
      state.textContent = 'Não foi possível copiar. Selecione e copie o valor.';
      state.classList.remove('radio-copy-success');
    }
  }
  const hasFieldValue = value => value != null && !['', '-', '—'].includes(String(value).trim());
  function description(client) {
    const dl = document.createElement('dl');
    for (const [label, value] of overviewFields(client)) {
      const dt = document.createElement('dt'), dd = document.createElement('dd');
      const property = document.createElement('div'); property.className = 'radio-property';
      dt.textContent = label;
      const text = document.createElement('span'); text.textContent = value; dd.append(text);
      if (['MAC', 'Login'].includes(label)) {
        const copy = document.createElement('button'); copy.type = 'button';
        copy.className = 'system-button system-button-icon-only'; copy.title = label === 'Login' ? 'Copiar login' : 'Copiar MAC';
        copy.setAttribute('aria-label', copy.title); copy.disabled = !hasFieldValue(value);
        const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        icon.setAttribute('class', 'ui-icon'); icon.setAttribute('aria-hidden', 'true');
        const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
        use.setAttribute('href', '/icons.svg#copy'); icon.append(use); copy.append(icon);
        copy.onclick = () => copyDetails(value, `${label} copiado.`);
        dd.append(copy);
      }
      property.append(dt, dd); dl.append(property);
    }
    return dl;
  }
  function addSignalBars(cell, collection) {
    if (collection?.data.metrics?.signal?.mean == null) return;
    const level = collection.data.signalSeverity;
    const bars = document.createElement('span'); bars.className = 'signal-bars signal-bars-trailing'; bars.setAttribute('aria-hidden', 'true');
    if (level != null) bars.dataset.level = level;
    for (let i = 0; i < 4; i++) { const bar = document.createElement('i'); bar.dataset.inactive = String(level != null && i >= 4 - level); bars.append(bar); }
    const value = document.createElement('span'); value.textContent = cell.textContent;
    const content = document.createElement('span'); content.className = 'signal-value-with-bars';
    content.append(value, bars); cell.replaceChildren(content);
  }
  let sortKey = 'client', direction = 1, clientPage = 1;
  const pickers = new Map();
  const marked = kind => new Set([...$('incident-radio-' + kind).selectedOptions].map(option => option.value));
  function setupPicker(kind, choices, allLabel, selectedLabel) {
    const select = $('incident-radio-' + kind), previous = marked(kind);
    pickers.get(kind)?.dispose();
    select.replaceChildren(...choices.map(([value, label]) => {
      const option = document.createElement('option'); option.value = value; option.textContent = label; option.selected = previous.has(value); return option;
    }));
    $('incident-radio-' + kind + '-options').replaceChildren();
    pickers.set(kind, window.setupMultiPicker('radio-' + kind, select, allLabel, () => { clientPage = 1; render(); }, { allText:allLabel, selectedLabel, preserveSelection:true }));
  }
  function setupFilters() {
    const groups = new Map(clients.flatMap(client => (client.groups || []).map(group => [group.id, group.name])));
    setupPicker('group', [...groups].sort((a, b) => a[1].localeCompare(b[1], 'pt-BR')), 'Todos os grupos de hosts', 'grupos selecionados');
    setupPicker('client', clients.map(c => [c.id, c.login || c.provisional_mac || c.lastSeen?.mac || '-']), 'Todos os clientes', 'clientes selecionados');
    const aps = new Map(clients.map(c => { const v = tableValues(c); return [v.apId, v.ap]; }).filter(([id]) => id));
    setupPicker('ap', [...aps].sort((a, b) => a[1].localeCompare(b[1], 'pt-BR')), 'Todos os APs', 'APs selecionados');
    setupPicker('status', [['normal','Normal'], ['attention','Atenção'], ['critical','Crítico'], ['unknown','Sem informação']], 'Todas as conexões', 'condições selecionadas');
  }
  const tableFormat = (value, unit = '') => value == null ? '-' : format(value, unit);
  function render() {
    const visible = filterClients(clients, marked('client'), marked('ap'), marked('status'), marked('group'))
      .sort((a, b) => compareClients(a, b, sortKey, direction));
    const pagination = paginateClients(visible, clientPage); clientPage = pagination.page;
    $('radio-clients-previous').disabled = !pagination.previous;
    $('radio-clients-next').disabled = !pagination.next;
    $('radio-clients-count').textContent = `Exibindo ${pagination.start}–${pagination.end} de ${pagination.total} clientes`;
    $('radio-rows').replaceChildren(...pagination.rows.map(client => {
      const row = document.createElement('tr'), v = tableValues(client);
      const heading = document.createElement('th'), link = document.createElement('a');
      heading.scope = 'row'; link.href = '#radio-dialog'; link.className = 'availability-host-name'; link.textContent = v.client;
      link.setAttribute('aria-haspopup', 'dialog'); link.setAttribute('aria-controls', 'radio-dialog');
      link.onclick = event => { event.preventDefault(); open(client, link); }; heading.append(link); row.append(heading);
      const values = [v.ap || '-', v.observed == null ? '-' : date(v.observed), v.statusLabel,
        tableFormat(v.signal, 'dBm'), tableFormat(v.snr, 'dB'), tableFormat(v.ccq, '%'), tableFormat(v.latency, 'ms'), tableFormat(v.loss, '%')];
      for (const value of values) { const cell = document.createElement('td'); cell.textContent = value; row.append(cell); }
      addSignalBars(row.children[4], client.latest);
      row.children[3].dataset.connection = v.status;
      return row;
    }));
    document.querySelectorAll('[data-radio-sort]').forEach(button => {
      const active = button.dataset.radioSort === sortKey;
      button.closest('th').setAttribute('aria-sort', active ? (direction === 1 ? 'ascending' : 'descending') : 'none');
      button.querySelector('span').textContent = active ? (direction === 1 ? '↑' : '↓') : '↕';
    });
    $('radio-state').textContent = visible.length ? `${visible.length} cliente(s). Os indicadores refletem a última coleta válida de cada cliente.` : 'Nenhum cliente encontrado para os filtros selecionados.';
  }
  async function load() {
    $('radio-state').textContent = 'Carregando clientes…';
    try { clients = await request(); setupFilters(); render(); } catch (error) { $('radio-state').textContent = error.message; }
  }
  function tab(history) {
    for (const name of ['overview', 'history']) {
      const active = history === (name === 'history');
      const panel = $('radio-' + name);
      panel.hidden = !active;
      if (active) panel.scrollTop = 0;
      $('radio-' + name + '-tab').setAttribute('aria-selected', String(active));
      $('radio-' + name + '-tab').tabIndex = active ? 0 : -1;
    }
    if (history) loadHistory();
  }
  function open(client, source) {
    selected = client; trigger = source; page = 0; generation++; pending = null;
    $('radio-title').textContent = client.login || client.provisional_mac || 'Cliente Rádio';
    $('radio-details').replaceChildren(description(client));
    const connection = tableValues(client);
    $('radio-summary-status').textContent = connection.statusLabel;
    for (const [key, severity] of [['signal', connection.signal == null ? null : client.latest?.data.signalSeverity],
      ['status', client.latest?.data.connectionSeverity]]) {
      $('radio-summary-' + key).closest('.radio-summary-card').dataset.severity = severity ?? 'unknown';
    }
    for (const [key, unit] of [['signal', 'dBm'], ['latency', 'ms'], ['loss', '%']])
      $('radio-summary-' + key).textContent = tableFormat(connection[key], unit);
    $('radio-binding').hidden = !!client.login; $('radio-binding').reset();
    const at = new Date(Number(client.observedFrom));
    $('radio-from').value = new Date(at - at.getTimezoneOffset() * 60000).toISOString().slice(0, 19);
    $('radio-binding-preview').textContent = ''; $('radio-dialog-state').textContent = '';
    $('radio-binding-confirm').hidden = true;
    tab(false); $('radio-dialog').showModal();
  }
  async function loadHistory() {
    const own = ++generation;
    $('radio-history-state').textContent = 'Carregando histórico…'; $('radio-history-items').replaceChildren();
    try {
      const items = await request('/' + selected.id + '/history?page=' + page);
      if (own !== generation) return;
      $('radio-history-items').replaceChildren(...items.map(item => {
        const row = document.createElement('tr'), heading = document.createElement('th'), link = document.createElement('a');
        heading.scope = 'row'; link.href = '#radio-collection-dialog'; link.className = 'availability-host-name'; link.textContent = item.mac || '—';
        link.setAttribute('aria-haspopup', 'dialog'); link.setAttribute('aria-controls', 'radio-collection-dialog');
        link.onclick = event => {
          event.preventDefault(); collectionTrigger = link;
          $('radio-collection-title').textContent = 'Detalhes da coleta';
          const values = new Map(collectionFields(item));
          values.set('Período da observação', observationPeriod(item));
          const distance = item.data.distance;
          if (distance != null && String(distance).trim() !== '' && Number.isFinite(Number(distance))) values.set('Distância', format(Number(distance), 'km'));
          const modal = $('radio-collection-dialog');
          modal.dataset.severity = item.data.connectionSeverity ?? 'unknown';
          modal.dataset.signalSeverity = item.data.signalSeverity ?? 'unknown';
          modal.querySelectorAll('[data-collection-field]').forEach(node => {
            const value = values.get(node.dataset.collectionField);
            node.textContent = value == null || value === '-' || value === 'Indisponível' ? '—' : value;
            if (node.dataset.collectionField === 'Período da observação') {
              node.title = `${date(item.observed_from)} – ${date(item.observed_to)}`;
              node.setAttribute('aria-label', node.title);
            }
          });
          $('radio-collection-login').textContent = selected.login || item.data.observedLogin || '—';
          const test = icmpDetails(item), content = $('radio-collection-icmp'); content.replaceChildren();
          content.classList.toggle('collection-icmp-empty', Boolean(test.message));
          if (test.message) {
            const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            icon.setAttribute('class', 'ui-icon'); icon.setAttribute('viewBox', '0 0 24 24'); icon.setAttribute('aria-hidden', 'true');
            const use = document.createElementNS('http://www.w3.org/2000/svg', 'use'); use.setAttribute('href', '/icons.svg#info'); icon.append(use);
            const message = document.createElement('p'); message.textContent = test.message; content.append(icon, message);
          }
          else {
            const dl = document.createElement('dl'); dl.className = 'collection-properties';
            for (const [label, value] of test.fields) {
              const row = document.createElement('div'), dt = document.createElement('dt'), dd = document.createElement('dd');
              dt.textContent = label; dd.textContent = value; row.append(dt, dd); dl.append(row);
            }
            content.append(dl);
            if (test.unavailable.length) { const note = document.createElement('p'); note.textContent = 'Indisponíveis: ' + test.unavailable.join(', ') + '.'; content.append(note); }
          }
          modal.showModal();
          $('radio-collection-details').scrollTop = 0;
        };
        heading.append(link); row.append(heading);
        for (const value of historyValues(item).slice(1)) { const cell = document.createElement('td'); cell.textContent = value; row.append(cell); }
        addSignalBars(row.children[3], item);
        row.children[4].dataset.connection = tableValues({ latest:item }).status;
        row.children[4].dataset.severity = item.data.connectionSeverity ?? 'unknown';
        return row;
      }));
      $('radio-history-state').textContent = items.length ? '' : 'Nenhuma coleta neste período.';
      $('radio-history-page').textContent = `Exibindo ${items.length ? page * 20 + 1 : 0}–${items.length ? page * 20 + items.length : 0} de ${items.totalCount ?? items.length} coletas`;
      $('radio-history-previous').disabled = page === 0; $('radio-history-next').disabled = items.totalCount == null ? items.length < 20 : (page + 1) * 20 >= items.totalCount;
    } catch (error) { if (own === generation) $('radio-history-state').textContent = error.message; }
  }
  $('radio-binding').onsubmit = async event => {
    event.preventDefault(); $('radio-binding-confirm').hidden = true;
    pending = { login: $('radio-login').value.trim(), from: new Date($('radio-from').value).getTime(), confirm: false };
    try {
      const preview = await request('/' + selected.id + '/binding', pending);
      const periods = preview.periods.map(p => `${p.client_id}: ${date(p.valid_from)} até ${p.valid_to == null ? 'atual' : date(p.valid_to)}`).join('; ');
      $('radio-binding-preview').textContent = `Destino: ${preview.target?.login || pending.login} (${preview.target?.id || 'cadastro atual'}). ${preview.collections} coletas serão incorporadas. Início: ${date(preview.from)}. Vínculos anteriores: ${periods || 'nenhum'}.`;
      $('radio-binding-confirm').hidden = false;
    } catch (error) { $('radio-binding-preview').textContent = error.message; }
  };
  for (const name of ['radio-login', 'radio-from']) $(name).oninput = () => { pending = null; $('radio-binding-confirm').hidden = true; };
  $('radio-binding-confirm').onclick = async () => {
    if (!pending) return;
    $('radio-binding-confirm').disabled = true;
    try { await request('/' + selected.id + '/binding', { ...pending, confirm: true }); $('radio-dialog').close(); await load(); }
    catch (error) {
      $('radio-dialog-state').classList.remove('radio-copy-success');
      $('radio-dialog-state').textContent = error.message;
    }
    finally { $('radio-binding-confirm').disabled = false; }
  };
  $('radio-overview-tab').onclick = () => tab(false); $('radio-history-tab').onclick = () => tab(true);
  for (const name of ['overview', 'history']) $('radio-' + name + '-tab').onkeydown = event => {
    if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
      event.preventDefault(); const history = event.key === 'End' || (event.key !== 'Home' && name === 'overview');
      tab(history); $('radio-' + (history ? 'history' : 'overview') + '-tab').focus();
    }
  };
  $('radio-history-previous').onclick = () => { page--; loadHistory(); };
  $('radio-history-next').onclick = () => { page++; loadHistory(); };
  let collectionTrigger;
  $('radio-collection-close-top').onclick = $('radio-collection-cancel').onclick = () => $('radio-collection-dialog').close();
  $('radio-collection-dialog').addEventListener('close', () => collectionTrigger?.focus());
  $('radio-close').onclick = $('radio-cancel').onclick = () => $('radio-dialog').close();
  $('radio-dialog').addEventListener('close', () => { generation++; if ($('radio-collection-dialog').open) $('radio-collection-dialog').close(); trigger?.focus(); });
  document.querySelectorAll('[data-radio-sort]').forEach(button => { button.onclick = () => { const key = button.dataset.radioSort; direction = key === sortKey ? -direction : 1; sortKey = key; clientPage = 1; render(); }; });
  $('radio-clients-previous').onclick = () => { clientPage--; render(); };
  $('radio-clients-next').onclick = () => { clientPage++; render(); };
  load();
})();
