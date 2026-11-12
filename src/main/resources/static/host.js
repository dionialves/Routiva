(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const root = document.querySelector('[data-host-id]');
  const base = '/api/hosts/' + encodeURIComponent(root.dataset.hostId);
  const signalPolicy = window.HostSignal.policy();
  let initialTab = true, overviewAt = 0, wirelessAvailable = false;
  let lastValidAt = null, interfacesAt = 0, interfacesAvailable = false;
  let tab = 'interfaces', interfaces = [], clients = [], traffic = [];
  let interfaceCounters = new Map();
  let wirelessMeasuredAt, wirelessBaseline = null, wirelessTraffic = [];
  let liveTimer, historyTimer, controller, historyController;
  let generation = 0, selectedClient = null, selectedInterface = '', interfaceTrigger;
  const labels = { identity:'Identificação', 'board-name':'Modelo', version:'RouterOS', uptime:'Tempo ligado',
    'cpu-load':'CPU', 'free-memory':'Memória livre', 'total-memory':'Memória total', frequency:'Frequência', channel:'Canal', ssid:'SSID',
    name:'Nome', type:'Tipo', running:'Em operação', disabled:'Desabilitada', mtu:'MTU',
    'actual-mtu':'MTU efetiva', 'mac-address':'MAC', 'rx-byte':'Download acumulado (bytes)', 'tx-byte':'Upload acumulado (bytes)',
    'rx-error':'Erros RX', 'tx-error':'Erros TX', 'rx-drop':'Descartes RX', 'tx-drop':'Descartes TX',
    'link-downs':'Quedas de link' };
  function details(id, row, keys = Object.keys(row || {}).filter(key => !['downloadRate', 'uploadRate', '.id'].includes(key))) {
    const fragment = document.createDocumentFragment();
    for (const key of keys) {
      const box = document.createElement('div'), dt = document.createElement('dt'), dd = document.createElement('dd');
      dt.textContent = labels[key] || key; dd.textContent = resourceValue(key, row?.[key]);
      box.append(dt, dd); fragment.append(box);
    }
    $(id).replaceChildren(fragment);
  }
  function resourceValue(key, value) {
    if (value == null || value === '') return '—';
    if (key.endsWith('memory')) {
      const bytes = Number(value);
      if (!Number.isFinite(bytes) || bytes < 0) return '—';
      return (bytes / (bytes >= 1073741824 ? 1073741824 : 1048576)).toLocaleString('pt-BR', { maximumFractionDigits:2 }) + (bytes >= 1073741824 ? ' GiB' : ' MiB');
    }
    if (key === 'cpu-load') return value + '%';
    if (key === 'frequency') {
      return String(value).split(' / ').map(frequency =>
        /^\d+(?:\.\d+)?$/.test(frequency) ? frequency + ' MHz' : frequency
      ).join(' / ');
    }
    if (key === 'uptime') {
      const seconds = wirelessSortValue({ uptime:value }, 'uptime');
      if (typeof seconds === 'number') return `${Math.floor(seconds / 86400)}d ${Math.floor(seconds % 86400 / 3600)}h ${Math.floor(seconds % 3600 / 60)}m`;
    }
    return value;
  }
  let previousSummary = null;
  function clientTrend(id, delta, change) {
    const trend = $(id);
    trend.hidden = !delta;
    if (!delta) return;
    trend.textContent = (delta > 0 ? '↑ ' : '↓ ') + Math.abs(delta);
    trend.title = 'Variação desde a leitura anterior';
    trend.dataset.change = change;
  }
  function signalSummary() {
    const summary = signalPolicy.summarize(clients);
    $('connected-clients').textContent = wirelessAvailable ? summary.total : '—';
    $('weak-clients').textContent = wirelessAvailable ? summary.counts[2] + summary.counts[3] : '—';
    const weak = summary.counts[2] + summary.counts[3];
    const comparable = wirelessAvailable && previousSummary && wirelessMeasuredAt > previousSummary.at;
    const connectedDelta = comparable ? summary.total - previousSummary.total : null;
    const weakDelta = comparable && !summary.missing && !previousSummary.missing ? weak - previousSummary.weak : null;
    clientTrend('connected-trend', connectedDelta, 'neutral');
    clientTrend('weak-trend', weakDelta, weakDelta > 0 ? 'worse' : 'better');
    previousSummary = wirelessAvailable ? { ...summary, weak, at:wirelessMeasuredAt } : null;
    $('weak-description').textContent = 'Clientes em estado ruim ou crítico';
    const badges = $('wireless-summary');
    badges.replaceChildren();
    const counts = [[summary.total, 'conectados'], [summary.counts[3], 'críticos'],
      [summary.counts[2], 'ruins'], [summary.counts[1], 'em atenção']];
    if (wirelessAvailable) {
      for (const [count, label] of counts) {
        const badge = document.createElement('span');
        badge.textContent = `${count} ${label}`;
        badges.append(badge);
      }
    } else badges.textContent = 'Registration Table indisponível';

    const box = $('signal-distribution'), donut = document.createElement('div');
    donut.className = 'signal-donut';
    const center = document.createElement('span'), total = document.createElement('strong');
    total.textContent = wirelessAvailable ? summary.total : '—';
    center.append(total, document.createTextNode('clientes'));
    donut.append(center);
    const legend = document.createElement('ul'), segments = [], description = [];
    let offset = 0;
    const categories = signalPolicy.names.map((name, i) => [name, summary.counts[i], signalPolicy.colors[i]]);
    if (wirelessAvailable && summary.missing) categories.push(['Indisponível', summary.missing, '#94a3b8']);
    for (const [name, count, color] of categories) {
      const share = summary.total ? count / summary.total * 100 : 0;
      if (count) segments.push(`${color} ${offset}% ${offset + share}%`);
      offset += share;
      const item = document.createElement('li'), label = document.createElement('span');
      const value = document.createElement('strong');
      label.textContent = name;
      label.style.borderColor = color;
      value.textContent = wirelessAvailable ? `${count} (${number(share)}%)` : '—';
      item.append(label, value);
      legend.append(item);
      description.push(name + ' ' + value.textContent);
    }
    if (wirelessAvailable && segments.length) donut.style.background = `conic-gradient(${segments.join(',')})`;
    box.replaceChildren(donut, legend);
    box.setAttribute('aria-label', description.join(', '));
    $('signal-missing').textContent = wirelessAvailable ? '' : 'Aguardando dados válidos da API';
  }
  async function request(path, options = {}) {
    const response = await fetch((/^\/(overview|interfaces|traffic|wireless)(?:\?|$)/.test(path) ? '/api/ap/' + encodeURIComponent(root.dataset.hostId) : base) + path, { cache:'no-store', ...options });
    if (!response.ok) {
      let message = 'Não foi possível consultar o equipamento.';
      try { const body = await response.json(); message = body.message || body.detail || message; } catch (_) { /* Empty response. */ }
      throw new Error(message);
    }
    return response.json();
  }
  const number = value => value == null ? 'Sem dados' : Number(value).toLocaleString('pt-BR', { maximumFractionDigits:2 });
  function chart(container, title, samples, series, unit) {
    const box = document.createElement('div'); box.className = 'host-chart';
    const heading = document.createElement('h3'); heading.textContent = title; box.append(heading);
    const ns = 'http://www.w3.org/2000/svg', svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 600 170'); svg.setAttribute('role', 'img'); svg.setAttribute('aria-label', title);
    const values = samples.flatMap(p => series.map(s => p[s.key])).filter(Number.isFinite);
    const max = unit === '%' ? 100 : Math.max(1, ...values) * 1.1;
    for (const s of series) {
      let path = '', connected = false;
      samples.forEach((point, i) => {
        if (!Number.isFinite(point[s.key])) { connected = false; return; }
        const x = 12 + i / Math.max(1, samples.length - 1) * 576, y = 150 - point[s.key] / max * 130;
        path += `${connected ? 'L' : 'M'}${x},${y} `; connected = true;
        const dot = document.createElementNS(ns, 'circle'); dot.setAttribute('cx', x); dot.setAttribute('cy', y);
        dot.setAttribute('r', '2'); dot.setAttribute('fill', s.color); svg.append(dot);
      });
      const line = document.createElementNS(ns, 'path'); line.setAttribute('d', path);
      line.setAttribute('fill', 'none'); line.setAttribute('stroke', s.color); line.setAttribute('stroke-width', '2'); svg.append(line);
      const legend = document.createElement('p'); legend.style.color = s.color;
      legend.textContent = unit === 'bits/s'
        ? `${s.label}: ${window.HostTraffic.format(samples.at(-1)?.[s.key])} · escala 0–${window.HostTraffic.format(max)}`
        : `${s.label}: ${number(samples.at(-1)?.[s.key])} ${unit} · escala 0–${number(max)} ${unit}`; box.append(legend);
    }
    box.append(svg);
    const range = document.createElement('p');
    range.textContent = samples.length ? `${new Date(samples[0].at).toLocaleString('pt-BR')} — ${new Date(samples.at(-1).at).toLocaleString('pt-BR')}` : 'Aguardando amostras';
    box.append(range); container.replaceChildren(box);
  }
  function historyMetrics(report) {
    for (const key of ['operational', 'technical', 'loss', 'latency']) {
      const value = report?.total?.[key];
      $(key).textContent = report == null ? '—' : window.AvailabilityView.format(value, key === 'latency' ? ' ms' : '%');
    }
    window.AvailabilityView.times(report);
  }
  let lastPingAt = null, lastBucketLoss = null;
  function equipmentState() {
    const age = lastPingAt == null ? Infinity : Date.now() - lastPingAt;
    const recent = age >= 0 && age <= 10000;
    const online = root.dataset.api === 'online' || (recent && lastBucketLoss === 0);
    const offline = !online && recent && lastBucketLoss === 100;
    const badge = $('equipment-online');
    badge.textContent = online ? 'Online' : offline ? 'Offline (ICMP)' : 'Estado desconhecido';
    badge.dataset.state = online ? 'online' : offline ? 'offline' : 'unknown';
    badge.title = root.dataset.api === 'online' ? 'O equipamento respondeu à API.'
      : recent ? 'Disponibilidade ICMP no intervalo da última medição.' : 'Sem confirmação recente de alcance.';
  }
  let historyGeneration = 0, displayedHistoryPeriod = null;
  async function history(background = false) {
    const period = $('history-period').value;
    const preserve = background === true && displayedHistoryPeriod === period;
    const ownGeneration = ++historyGeneration;
    historyController?.abort(); historyController = new AbortController();
    if (!preserve) {
      displayedHistoryPeriod = null;
      $('history-state').textContent = 'Carregando histórico…'; window.HostHistoryCharts.clear();
      $('history-range').textContent = 'Carregando período…'; historyMetrics(null);
    }
    try {
      const report = await request('/availability?minutes=' + period, { signal:historyController.signal });
      if (ownGeneration !== historyGeneration) return;
      displayedHistoryPeriod = period;
      historyMetrics(report);
      lastPingAt = report.lastSampleAt;
      lastBucketLoss = report.series.find(p => p.from <= lastPingAt && p.to > lastPingAt)?.metrics.loss;
      equipmentState();
      $('history-range').textContent = `${new Date(report.from).toLocaleString('pt-BR')} — ${new Date(report.to).toLocaleString('pt-BR')}`;
      $('history-state').textContent = '';
      window.HostHistoryCharts.update(report);
    } catch (error) {
      if (error.name !== 'AbortError' && ownGeneration === historyGeneration) {
        if (preserve) {
          $('history-state').textContent = 'Não foi possível atualizar os indicadores TraceOne. Exibindo a última consulta.';
          return;
        }
        historyMetrics(null); window.HostHistoryCharts.clear('Histórico indisponível.');
        $('history-range').textContent = 'Período indisponível';
        $('history-state').textContent = 'Histórico indisponível: ' + error.message;
      }
    }
  }
  const wirelessLabels = {
    'quality-reason':'Diagnóstico da conexão', 'critical-alert':'Alerta da última leitura',
    'radio-name':'Nome do rádio', interface:'Interface', 'mac-address':'MAC', source:'Recurso',
    uptime:'Tempo conectado', 'last-activity':'Última atividade', 'routeros-version':'RouterOS do cliente',
    ap:'Ponto de acesso', wds:'WDS', bridge:'Bridge', nstreme:'Nstreme', 'framing-mode':'Modo de enquadramento',
    signal:'Sinal (dBm)', 'signal-strength':'Sinal (dBm)', 'signal-to-noise':'Relação sinal/ruído (dB)',
    'signal-strength-ch0':'Sinal da cadeia 0 (dBm)', 'signal-strength-ch1':'Sinal da cadeia 1 (dBm)',
    'strength-at-rates':'Sinal por taxa', 'tx-ccq':'Qualidade de transmissão — CCQ (%)', distance:'Distância informada',
    'rx-rate':'Taxa do enlace recebida pelo AP (RX)', 'tx-rate':'Taxa do enlace enviada pelo AP (TX)', 'p-throughput':'Throughput estimado (kbps)',
    packets:'Pacotes (TX / RX)', bytes:'Tráfego acumulado (TX / RX, bytes)', frames:'Quadros (TX / RX)',
    'frame-bytes':'Bytes dos quadros (TX / RX)', 'hw-frames':'Quadros de hardware (TX / RX)',
    'hw-frame-bytes':'Bytes de hardware (TX / RX)', 'tx-frames-timed-out':'Quadros TX com timeout',
    'authentication-type':'Autenticação', encryption:'Criptografia', 'group-encryption':'Criptografia do grupo',
    'management-protection':'Proteção de gerenciamento', '802.1x-port-enabled':'Porta 802.1X habilitada',
    'wmm-enabled':'WMM habilitado', compression:'Compressão', authorized:'Autorizado',
  };
  function wirelessDetails(client) {
    const groups = [
      ['Identificação', ['radio-name','mac-address','interface','source','routeros-version']],
      ['Sinal e qualidade', ['quality-reason','critical-alert','signal','signal-strength','signal-to-noise','signal-strength-ch0','signal-strength-ch1','strength-at-rates','tx-ccq','distance']],
      ['Taxas e tráfego', ['rx-rate','tx-rate','p-throughput','bytes','packets','frames','frame-bytes','hw-frames','hw-frame-bytes','tx-frames-timed-out']],
    ];
    const fragment = document.createDocumentFragment();
    for (const [title, keys] of groups) {
      const present = keys.filter(key => client[key] != null && client[key] !== '');
      if (!present.length) continue;
      const section = document.createElement('section'), heading = document.createElement('h3'), list = document.createElement('dl');
      heading.textContent = title; list.className = 'host-details wireless-detail-grid';
      for (const key of present) {
        const box = document.createElement('div'), dt = document.createElement('dt'), dd = document.createElement('dd');
        dt.textContent = wirelessLabels[key] || key;
        const value = client[key];
        dd.textContent = value === 'true' ? 'Sim' : value === 'false' ? 'Não' : value;
        box.append(dt, dd); list.append(box);
      }
      section.append(heading, list); fragment.append(section);
    }
    $('wireless-details').replaceChildren(fragment);
    $('wireless-modal-title').textContent = client['radio-name'] || client['mac-address'] || 'Cliente wireless';
    $('wireless-modal-subtitle').textContent = [client['mac-address'], client.interface].filter(Boolean).join(' · ');
  }
  let ixcGeneration = 0;
  function clearIxc() {
    ixcGeneration++;
    $('ixc-details').replaceChildren();
    $('ixc-state').textContent = '';
    $('ixc-query').disabled = false;
  }
  $('ixc-query').onclick = async () => {
    if (!selectedClient) return;
    const ownGeneration = ++ixcGeneration;
    $('ixc-details').replaceChildren();
    $('ixc-state').textContent = 'Consultando IXC…';
    $('ixc-query').disabled = true;
    try {
      const logins = await request('/ixc/login?mac=' + encodeURIComponent(selectedClient.mac));
      if (ownGeneration !== ixcGeneration) return;
      $('ixc-state').textContent = logins.length ? `${logins.length} cadastro(s) com este MAC` : 'Nenhum login encontrado para este MAC.';
      for (const login of logins) {
        const section = document.createElement('section'), title = document.createElement('h4'), list = document.createElement('dl');
        title.textContent = 'Login IXC ' + login.id; list.className = 'host-details';
        for (const [key, label, secret] of [['ip','IP'], ['pppoe','PPPoE'], ['pppoePassword','Senha PPPoE',true], ['equipmentPassword','Senha roteador',true], ['connectionPort','Porta Roteador']]) {
          const box = document.createElement('div'), dt = document.createElement('dt'), dd = document.createElement('dd');
          dt.textContent = label;
          if (secret && login[key]) {
            const input = document.createElement('input'), toggle = document.createElement('button');
            input.type = 'password'; input.value = login[key]; input.readOnly = true;
            input.autocomplete = 'off'; input.setAttribute('aria-label', label);
            toggle.type = 'button'; toggle.textContent = 'Mostrar';
            toggle.onclick = () => { input.type = input.type === 'password' ? 'text' : 'password'; toggle.textContent = input.type === 'password' ? 'Mostrar' : 'Ocultar'; };
            dd.append(input, toggle);
          } else dd.textContent = login[key] || 'Não informado no IXC';
          box.append(dt, dd); list.append(box);
        }
        section.append(title, list); $('ixc-details').append(section);
      }
    } catch (error) {
      if (ownGeneration === ixcGeneration) $('ixc-state').textContent = error.message;
    } finally {
      if (ownGeneration === ixcGeneration) $('ixc-query').disabled = false;
    }
  };
  function wirelessBandwidth(client, at) {
    const rates = window.HostTraffic.wirelessRates(wirelessBaseline, client, at);
    wirelessBaseline = rates.baseline;
    $('wireless-download').textContent = window.HostTraffic.format(rates.download);
    $('wireless-upload').textContent = window.HostTraffic.format(rates.upload);
    const hasCounters = Object.values(window.HostTraffic.wirelessCounters(client)).every(value => /^\d+$/.test(String(value)));
    $('wireless-traffic-state').textContent = rates.download != null || rates.upload != null
      ? 'Tráfego atualizado às ' + new Date(at).toLocaleTimeString('pt-BR')
      : hasCounters ? 'Aguardando a próxima medição para calcular a velocidade.' : 'O equipamento não informou contadores de tráfego deste cliente.';
    wirelessTraffic.push({ at, download:rates.download, upload:rates.upload });
    wirelessTraffic = wirelessTraffic.slice(-120);
    chart($('wireless-traffic-chart'), 'Download e Upload do cliente', wirelessTraffic,
      [{ key:'download', label:'Download', color:'#00d1ff' }, { key:'upload', label:'Upload', color:'#14b8a6' }], 'bits/s');
  }
  function resetWirelessBandwidth(message) {
    wirelessBaseline = null; wirelessTraffic = [];
    $('wireless-download').textContent = 'Indisponível'; $('wireless-upload').textContent = 'Indisponível';
    $('wireless-traffic-chart').replaceChildren(); $('wireless-traffic-state').textContent = message;
  }
  const loginNames = new Map();
  let loadingNames = false;
  const macKey = mac => String(mac || '').replace(/[:.\-]/g, '').toUpperCase();
  function pppoeName(client) {
    const entry = loginNames.get(macKey(client['mac-address']));
    if (entry?.names) return entry.names.join(' / ') || 'Sem login vinculado';
    return entry?.error ? 'IXC indisponível' : 'Consultando IXC…';
  }
  async function loadLoginNames() {
    if (loadingNames || document.hidden || tab !== 'wireless') return;
    const active = new Map(clients.map(client => [macKey(client['mac-address']), client['mac-address']])
      .filter(([key]) => /^[0-9A-F]{12}$/.test(key)));
    for (const key of loginNames.keys()) if (!active.has(key)) loginNames.delete(key);
    if (![...active.keys()].some(key => !loginNames.has(key) || Date.now() - loginNames.get(key).at >= 60000)) return;
    loadingNames = true;
    try {
      const result = await request('/ixc/login-names');
      const byMac = new Map(Object.entries(result).map(([mac, names]) => [macKey(mac), names]));
      for (const key of active.keys()) loginNames.set(key, { names:byMac.get(key) || [], at:Date.now() });
      $('wireless-ixc-state').textContent = '';
    } catch (error) {
      for (const key of active.keys()) {
        loginNames.set(key, { names:loginNames.get(key)?.names, error:error.message, at:Date.now() });
      }
      $('wireless-ixc-state').textContent = 'Consulta de logins PPPoE indisponível. Nova tentativa em até 60 segundos. ' + error.message;
    } finally {
      loadingNames = false;
      wireless();
    }
  }
  let wirelessSort = { key:null, direction:1 };
  function wirelessSortValue(client, key) {
    if (key === 'signal') return signalPolicy.signal(client);
    if (key === 'quality') { const category = signalPolicy.category(client); return category < 0 ? null : category; }
    if (key.startsWith('avg-')) return client[key] == null ? null : Number(client[key]);
    const value = client[key] ?? (key === 'signal' ? client['signal-strength'] : null);
    if (value == null || value === '') return null;
    if (key === 'signal' || key === 'tx-ccq' || key === 'p-throughput' || key === 'distance') {
      const parsed = parseFloat(value); return Number.isFinite(parsed) ? parsed : null;
    }
    if (key === 'tx-rate' || key === 'rx-rate') {
      const match = String(value).match(/^([\d.]+)\s*([kmg]?)bps/i);
      return match ? Number(match[1]) * ({ '':1, k:1000, m:1000000, g:1000000000 }[match[2].toLowerCase()]) : null;
    }
    if (key === 'uptime') {
      const matches = [...String(value).matchAll(/([\d.]+)(ms|w|d|h|m|s)/g)];
      return matches.length ? matches.reduce((sum, match) => sum + Number(match[1]) * ({ w:604800, d:86400, h:3600, m:60, s:1, ms:0.001 }[match[2]]), 0) : null;
    }
    return String(value);
  }
  function sortedClients() {
    const namedClients = clients.map(client => ({ ...client, pppoe:pppoeName(client) }));
    if (!wirelessSort.key) {
      return namedClients.sort((a, b) => signalPolicy.category(b) - signalPolicy.category(a)
        || Number(Boolean(b['critical-alert'])) - Number(Boolean(a['critical-alert'])));
    }
    return namedClients.sort((a, b) => {
      const left = wirelessSortValue(a, wirelessSort.key), right = wirelessSortValue(b, wirelessSort.key);
      if (left == null || right == null) return left == null ? right == null ? 0 : 1 : -1;
      return wirelessSort.direction * (typeof left === 'number' ? left - right : left.localeCompare(right, 'pt-BR', { numeric:true, sensitivity:'base' }));
    });
  }
  function replaceRows(id, fragment, key) {
    const active = document.activeElement;
    const row = active?.closest?.('tr');
    const identity = row?.dataset[key];
    const clientInterface = row?.dataset.clientInterface;
    const within = row?.parentElement === $(id);
    const button = active?.tagName === 'BUTTON';
    $(id).replaceChildren(fragment);
    if (within && identity) {
      const replacement = Array.from($(id).children).find(item => item.dataset[key] === identity && item.dataset.clientInterface === clientInterface);
      (button ? replacement?.querySelector('button') : replacement)?.focus({ preventScroll:true });
    }
  }
  function wireless(measuredAt) {
    const fragment = document.createDocumentFragment();
    for (const client of sortedClients()) {
      const tr = document.createElement('tr');
      tr.dataset.clientMac = client['mac-address']; tr.dataset.clientInterface = client.interface;
      const severity = signalPolicy.category(client);
      tr.dataset.quality = String(severity);

      tr.dataset.connection = client['connection-status'] || 'connected';
      tr.title = [client['quality-reason'] || client.quality || 'Avaliação indisponível',
        client['connection-status'] === 'disconnected' ? 'Desconectado · últimos valores' : client['critical-alert']].filter(Boolean).join(' · ');
      tr.setAttribute('aria-label', `${client['mac-address']} · ${client.quality || 'Avaliação indisponível'}${client['connection-status'] === 'disconnected' ? ' · Desconectado' : ''}${client['critical-alert'] ? ' · Crítico agora' : ''}`);
      const keys = ['mac-address', 'pppoe', 'interface', 'signal', 'avg-signal-to-noise', 'avg-tx-ccq', 'p-throughput', 'distance', 'uptime', 'avg-tx-rate', 'avg-rx-rate'];
      keys.forEach((key, index) => {
        const td = document.createElement('td');
        let value = client[key] ?? (key === 'signal' ? client['signal-strength'] : null);
        if (key === 'p-throughput') {
          const throughput = value == null || value === '' ? NaN : Number(value);
          value = Number.isFinite(throughput) && throughput >= 0 ? number(throughput) : null;
        }
        if (key.startsWith('avg-') && value != null) {
          value = number(Number(value)) + (key === 'avg-signal' ? ' dBm' : key === 'avg-signal-to-noise' ? ' dB' : key.endsWith('ccq') ? '%' : ' Mbps');
        }
        value ??= '—';
        if (index === 0) { const button = document.createElement('button'); button.type = 'button'; button.textContent = value;
          button.setAttribute('aria-haspopup', 'dialog');
          button.onclick = () => {
            clearIxc();
            selectedClient = { mac:client['mac-address'], interface:client.interface };
            wirelessBaseline = null; wirelessTraffic = [];
            wirelessBandwidth(client, wirelessMeasuredAt);
            wirelessDetails(client);
            $('wireless-live-state').textContent = 'Dados ao vivo · atualização a cada 1 segundo';
            $('wireless-modal').showModal();
          }; td.append(button);
        } else if (key === 'signal') {
          const bars = document.createElement('span'); bars.className = 'signal-bars'; bars.setAttribute('aria-hidden', 'true');
          for (let i = 0; i < 4; i++) bars.append(document.createElement('i'));
          td.className = 'signal-cell'; td.append(bars, document.createTextNode(signalPolicy.signal(client) == null ? '—' : number(signalPolicy.signal(client)) + ' dBm'));
        } else { td.textContent = value; if (key === 'pppoe') td.title = client['radio-name'] || client.comment || client.name || ''; }
        const metric = key === 'signal' ? key : key.startsWith('avg-') ? key.slice(4) : null;
        if (metric && client[metric + '-severity'] != null) td.dataset.level = client[metric + '-severity'];
        tr.append(td);
      });
      tr.tabIndex = 0; tr.onclick = event => { if (event.target.tagName !== 'BUTTON') tr.querySelector('button')?.click(); };
      tr.onkeydown = event => { if (event.target === tr && ['Enter', ' '].includes(event.key)) { event.preventDefault(); tr.querySelector('button')?.click(); } };
      fragment.append(tr);
    }
    replaceRows('wireless-clients', fragment, 'clientMac');
    if ($('wireless-modal').open && selectedClient) {
      const selected = clients.find(c => c['mac-address'] === selectedClient.mac && c.interface === selectedClient.interface);
      if (selected && selected['connection-status'] !== 'disconnected') {
        wirelessDetails(selected);
        if (typeof measuredAt === 'number') wirelessBandwidth(selected, measuredAt);
        if (typeof measuredAt === 'number') $('wireless-live-state').textContent = 'Atualizado às ' + new Date(measuredAt).toLocaleTimeString('pt-BR');
      } else {
        clearIxc();
        $('ixc-query').disabled = true;
        resetWirelessBandwidth('Cliente desconectado.');
        $('wireless-details').replaceChildren();
        $('wireless-live-state').textContent = 'Este cliente não consta mais na lista de conectados.';
      }
    }
  }
  function interfaceIcon(type) {
    const paths = {
      ether:'M4 4h16v12h-4v4H8v-4H4ZM8 4v5m4-5v5m4-5v5',
      wireless:'M2 8a16 16 0 0 1 20 0M5 12a11 11 0 0 1 14 0m-11 4a6 6 0 0 1 8 0m-4 4h.01',
      bridge:'M3 3h6v6H3Zm12 0h6v6h-6ZM9 15h6v6H9ZM6 9v3h12V9m-6 3v3',
      vlan:'M3 3h8l10 10-8 8L3 11Zm4 4h.01',
      switch:'M3 9h18v11H3Zm4 4v3m5-3v3m5-3v3M7 9V4h10v5',
      other:'M3 3h6v6H3Zm12 12h6v6h-6ZM9 6h9v9M6 9v9h9',
    };
    const kind = /^(wlan|wifi|wireless)/.test(type) ? 'wireless' : Object.hasOwn(paths, type) ? type : 'other';
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    for (const [key, value] of Object.entries({ viewBox:'0 0 24 24', fill:'none', stroke:'currentColor', 'stroke-width':'1.75', 'stroke-linecap':'round', 'stroke-linejoin':'round', 'aria-hidden':'true', class:'interface-type-icon' })) svg.setAttribute(key, value);
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path'); path.setAttribute('d', paths[kind]); svg.append(path);
    return svg;
  }
  function interfaceRows() {
    const fragment = document.createDocumentFragment();
    const visible = [...interfaces].sort((a, b) => a.name.localeCompare(b.name, 'pt-BR', { numeric:true }));
    for (const item of visible) {
      const tr = document.createElement('tr');
      tr.tabIndex = 0;
      tr.dataset.interfaceId = item['.id'];
      tr.setAttribute('aria-haspopup', 'dialog');
      tr.setAttribute('aria-label', 'Abrir interface ' + item.name);
      const state = item.disabled === 'true' ? 'Desabilitada' : item.running === 'true' ? 'Ativa' : 'Inativa';
      for (const value of [item.name, item.type, state, item['actual-mtu'] ?? item.mtu,
        item['mac-address'], item.uptime ?? '—', window.HostTraffic.format(item.downloadRate),
        window.HostTraffic.format(item.uploadRate),
        `${item['rx-error'] ?? '—'} / ${item['tx-error'] ?? '—'}`]) {
        const td = document.createElement('td'); td.textContent = value ?? 'Indisponível'; tr.append(td);
      }
      tr.children[0].append(interfaceIcon(item.type || ''));
      tr.dataset.running = item.running === 'true' && item.disabled !== 'true' ? 'true' : 'false';
      const open = () => {
        interfaceTrigger = tr;
        selectedInterface = item['.id']; traffic = [];
        $('interface-modal-title').textContent = item.name;
        details('interface-details', item);
        $('traffic-chart').replaceChildren();
        $('interface-live-state').textContent = 'Consultando utilização ao vivo…';
        $('interface-modal').showModal();
        restart();
      };
      tr.onclick = open;
      tr.onkeydown = event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); open(); } };
      fragment.append(tr);
    }
    replaceRows('interface-rows', fragment, 'interfaceId');
    $('interfaces-state').textContent = interfaces.length ? `${interfaces.length} interfaces` : 'Nenhuma interface disponível.';
  }
  function closeInterface() {
    const previous = selectedInterface;
    selectedInterface = ''; traffic = [];
    const trigger = Array.from($('interface-rows').children).find(row => row.dataset.interfaceId === previous);
    (trigger || (interfaceTrigger?.isConnected ? interfaceTrigger : null))?.focus();
    restart();
  }
  async function poll() {
    if (document.hidden) return;
    const ownGeneration = generation;
    controller = new AbortController();
    try {
      const section = tab;
      let overview, overviewError;
      try {
        if (Date.now() - overviewAt >= 10000) {
          overview = await request('/overview', { signal:controller.signal });
          if (generation !== ownGeneration) return;
          const resource = overview.rows[0] || {};
          details('resource-details', resource, ['identity','board-name','version','uptime','cpu-load','free-memory','total-memory','frequency','channel','ssid']);
          $('equipment-model').textContent = resource['board-name'] || 'Equipamento MikroTik';
          $('equipment-identity').textContent = resource.identity || 'Identificação indisponível';
          overviewAt = Date.now();
        }
      } catch (error) {
        if (error.name === 'AbortError' || generation !== ownGeneration) return;
        overviewError = error;
      }
      // Wireless drives the live indicators even while the interfaces tab is selected.
      let wirelessResult;
      try {
        wirelessResult = await request('/wireless', { signal:controller.signal });
        if (generation !== ownGeneration) return;
        wirelessAvailable = !wirelessResult.warning;
        if (wirelessAvailable) clients = wirelessResult.rows;
        wirelessMeasuredAt = wirelessResult.measuredAt;

        signalSummary();
        if (initialTab) {
          initialTab = false;
          if (wirelessAvailable && signalPolicy.summarize(clients).total) { document.querySelectorAll('[data-tab]').forEach(b => { if (b.dataset.tab === 'wireless') b.onclick(); }); return; }
        }
      } catch (error) {
        if (error.name === 'AbortError') throw error;
        wirelessAvailable = false; signalSummary();
        $('wireless-state').textContent = 'Registration Table indisponível · ' + error.message;
      }
      let interfaceResult;
      if (section === 'interfaces' || Date.now() - interfacesAt >= 5000) {
        try {
          interfaceResult = await request('/interfaces', { signal:controller.signal });
          if (generation !== ownGeneration) return;
          interfacesAt = Date.now(); interfacesAvailable = true;
        } catch (error) {
          interfacesAvailable = false;
          $('interfaces-state').textContent = 'Interfaces indisponíveis · dados anteriores desatualizados';
          if (section === 'interfaces' || error.name === 'AbortError') throw error;
        }
      }
      const result = section === 'wireless' ? wirelessResult : interfaceResult;
      if (!result) throw new Error('Registration Table indisponível');
      if (generation !== ownGeneration) return;
      if (section === 'wireless') {
        if (!result.warning) clients = result.rows;
        wirelessMeasuredAt = result.measuredAt;
        wireless(result.measuredAt);
        $('wireless-state').textContent = result.warning
          ? result.warning + ' · últimos dados desatualizados · leitura válida: ' + (lastValidAt ? new Date(lastValidAt).toLocaleTimeString('pt-BR') : '—')
          : 'Taxas TX/RX de associação e CCQ: médias móveis de até 30 amostras.';
        loadLoginNames();
      }
      if (interfaceResult) {
        const nextCounters = new Map();
        interfaces = interfaceResult.rows.map(item => {
          const previous = interfaceCounters.get(item['.id']);
          const elapsed = interfaceResult.measuredAt - (previous?.at ?? interfaceResult.measuredAt);
          const sameInterface = previous?.name === item.name;
          nextCounters.set(item['.id'], { ...item, at:interfaceResult.measuredAt });
          return { ...item,
            downloadRate:sameInterface ? window.HostTraffic.rate(previous['rx-byte'], item['rx-byte'], elapsed) : null,
            uploadRate:sameInterface ? window.HostTraffic.rate(previous['tx-byte'], item['tx-byte'], elapsed) : null,
          };
        });
        interfaceCounters = nextCounters;

        interfaceRows();
        const selected = interfaces.find(i => i['.id'] === selectedInterface);
        if ($('interface-modal').open && !selected) {
          $('interface-live-state').textContent = 'A interface não está mais disponível. Feche esta janela e selecione outra interface.';
          selectedInterface = '';
        }
        if ($('interface-modal').open && selected) details('interface-details', selected);
        if ($('interface-modal').open && selected) {
          const sample = await request('/traffic?selectedInterface=' + encodeURIComponent(selectedInterface), { signal:controller.signal });
          if (generation !== ownGeneration) return;
          $('interface-live-state').textContent = 'Atualizado às ' + new Date(sample.measuredAt).toLocaleTimeString('pt-BR');
          const row = sample.rows[0] || {};
          traffic.push({ at:sample.measuredAt, rx:row['rx-bits-per-second'] == null ? null : Number(row['rx-bits-per-second']), tx:row['tx-bits-per-second'] == null ? null : Number(row['tx-bits-per-second']) });
          traffic = traffic.slice(-120);
          chart($('traffic-chart'), 'Utilização da interface', traffic, [{ key:'rx', label:'Download', color:'#00d1ff' }, { key:'tx', label:'Upload', color:'#14b8a6' }], 'bits/s');
        }
      }
      if (overviewError) throw overviewError;
      document.querySelectorAll('[data-tab]').forEach(b => { b.textContent = b.dataset.tab === 'wireless' ? `Clientes wireless (${wirelessAvailable ? signalPolicy.summarize(clients).total : '—'})` : `Interfaces (${interfacesAvailable ? interfaces.length : '—'})`; });
      lastValidAt = result.measuredAt;
      $('host-api-state').textContent = 'API: Online';
      root.dataset.api = 'online';
      equipmentState();
      $('equipment-api').textContent = 'API conectada';
      $('host-updated').textContent = 'Última leitura válida: ' + new Date(lastValidAt).toLocaleString('pt-BR');
      $('live-error').hidden = true;
    } catch (error) {
      if (error.name !== 'AbortError' && generation === ownGeneration) {
        if ($('wireless-modal').open) { resetWirelessBandwidth('Consulta de tráfego indisponível.'); $('wireless-live-state').textContent = 'Atualização indisponível · dados anteriores podem estar desatualizados'; }
        if ($('interface-modal').open) $('interface-live-state').textContent = 'Atualização indisponível · dados anteriores podem estar desatualizados';
        if (tab === 'wireless') $('wireless-state').textContent = 'Falha na comunicação com o AP · dados desatualizados · últimos valores preservados';
        if (tab === 'interfaces' && traffic.length) traffic = [...traffic, { at:Date.now(), rx:null, tx:null }].slice(-120);
        $('host-api-state').textContent = 'API: indisponível · dados desatualizados';
        root.dataset.api = 'unavailable';
        equipmentState();
        $('equipment-api').textContent = 'API desconectada';
        $('live-error').textContent = error.message; $('live-error').hidden = false;
      }
    } finally { if (generation === ownGeneration && !document.hidden) liveTimer = setTimeout(poll, 1000); }
  }
  function restart() { generation++; controller?.abort(); clearTimeout(liveTimer); poll(); }
  document.querySelectorAll('[data-tab]').forEach(button => button.onclick = () => {
    initialTab = false;
    interfaceCounters.clear();
    tab = button.dataset.tab;
    document.querySelectorAll('[data-tab]').forEach(b => b.setAttribute('aria-pressed', String(b === button)));
    document.querySelectorAll('[data-panel]').forEach(p => { p.hidden = p.dataset.panel !== tab; }); restart();
  });
  $('interface-modal-close').onclick = () => $('interface-modal').close();
  $('interface-modal').onclose = closeInterface;
  $('interface-modal').onclick = event => {
    if (event.target !== $('interface-modal')) return;
    const bounds = $('interface-modal').getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) $('interface-modal').close();
  };
  $('wireless-modal-close').onclick = () => $('wireless-modal').close();
  $('wireless-modal').onclose = () => {
    const previous = selectedClient;
    clearIxc();
    selectedClient = null; wirelessBaseline = null; wirelessTraffic = [];
    const row = Array.from($('wireless-clients').children).find(item => item.dataset.clientMac === previous?.mac && item.dataset.clientInterface === previous?.interface);
    row?.querySelector('button')?.focus();
  };
  $('wireless-modal').onclick = event => {
    if (event.target !== $('wireless-modal')) return;
    const bounds = $('wireless-modal').getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) $('wireless-modal').close();
  };
  document.querySelectorAll('[data-wireless-sort]').forEach(button => {
    button.onclick = () => {
      const key = button.dataset.wirelessSort;
      wirelessSort = { key, direction:wirelessSort.key === key ? -wirelessSort.direction : 1 };
      document.querySelectorAll('[data-wireless-sort]').forEach(item => {
        const active = item.dataset.wirelessSort === key;
        item.closest('th').setAttribute('aria-sort', active ? wirelessSort.direction === 1 ? 'ascending' : 'descending' : 'none');
        item.querySelector('span').textContent = active ? wirelessSort.direction === 1 ? '↑' : '↓' : '↕';
      });
      wireless();
    };
  });
  $('history-period').onchange = history;
  $('host-period-filter').onsubmit = event => event.preventDefault();
  document.addEventListener('visibilitychange', () => { if (document.hidden) { if ($('wireless-modal').open) resetWirelessBandwidth('Atualização pausada enquanto a página estiver oculta.'); interfaceCounters.clear(); generation++; controller?.abort(); clearTimeout(liveTimer); } else restart(); });
  window.addEventListener('pagehide', () => { generation++; controller?.abort(); historyController?.abort(); clearTimeout(liveTimer); clearInterval(historyTimer); });
  historyTimer = setInterval(() => { if (!document.hidden) history(true); }, 60000);
  history(); poll();
})();
