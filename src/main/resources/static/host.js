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
    'cpu-load':'CPU', 'free-memory':'Memória livre', 'total-memory':'Memória total', frequency:'Frequência / Canal', channel:'Canal', ssid:'SSID',
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
    const counts = [[summary.total, 'conectados', null], [summary.counts[3], 'críticos', 3],
      [summary.counts[2], 'ruins', 2], [summary.counts[1], 'em atenção', 1]];
    if (wirelessAvailable) {
      for (const [count, label, quality] of counts) {
        const badge = document.createElement('button');
        badge.type = 'button';
        badge.setAttribute('aria-pressed', String(wirelessFilter === quality));
        badge.textContent = `${count} ${label}`;
        badge.onclick = () => {
          wirelessFilter = quality;
          wirelessSort = { key:null, direction:1 };
          document.querySelectorAll('[data-wireless-sort]').forEach(item => {
            item.closest('th').setAttribute('aria-sort', 'none');
            item.querySelector('span').textContent = '↕';
          });
          Array.from(badges.children).forEach((item, index) =>
            item.setAttribute('aria-pressed', String(counts[index][2] === quality)));
          wireless();
        };
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
      const error = new Error(message); error.status = response.status; throw error;
    }
    return response.json();
  }
  const number = value => value == null ? 'Sem dados' : Number(value).toLocaleString('pt-BR', { maximumFractionDigits:2 });
  const tableRadioNumber = value => Number(value).toLocaleString('pt-BR', { maximumFractionDigits:0 });
  const radioNumber = value => Number(value).toLocaleString('pt-BR', { minimumFractionDigits:2, maximumFractionDigits:2 });
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
  let clientIdentity = null, identityGeneration = 0, identityRadio = null;
  function copyRouterPassword(password) {
    const fallback = () => {
      const copy = event => { event.clipboardData.setData('text/plain', password); event.preventDefault(); };
      document.addEventListener('copy', copy);
      try {
        if (!document.execCommand?.('copy')) throw new Error('Clipboard unavailable');
      } finally { document.removeEventListener('copy', copy); }
    };
    if (navigator.clipboard?.writeText) return navigator.clipboard.writeText(password).catch(fallback);
    try { fallback(); return Promise.resolve(); } catch (error) { return Promise.reject(error); }
  }
  function renderClientIdentity() {
    if (!identityRadio) return;
    const title = $('wireless-modal-title'), subtitle = $('wireless-modal-subtitle');
    if (!clientIdentity?.pppoe) {
      title.textContent = identityRadio['radio-name'] || identityRadio['mac-address'] || 'Cliente wireless';
      subtitle.textContent = identityRadio['mac-address'] || '—';
      return;
    }
    title.textContent = clientIdentity.pppoe;
    const plan = document.createElement('span');
    const rate = value => String(value || '').replace(/k$/i, ' kbps').replace(/m$/i, ' Mbps');
    const download = rate(clientIdentity.plan?.download), upload = rate(clientIdentity.plan?.upload);
    plan.textContent = download || upload ? `Plano: ${download || '—'} ↓ / ${upload || '—'} ↑` : 'Velocidade do plano não informada';
    const ip = String(clientIdentity.ip || '').trim();
    const ipv4 = /^(?:0|[1-9]\d{0,2})(?:\.(?:0|[1-9]\d{0,2})){3}$/.test(ip) && ip.split('.').every(part => Number(part) <= 255) && ip !== '0.0.0.0';
    const port = String(clientIdentity.connectionPort || '').trim();
    const validPort = /^\d+$/.test(port) && Number(port) > 0 && Number(port) <= 65535;
    const address = document.createElement(ipv4 && (!port || validPort) ? 'a' : 'span');
    address.textContent = ip || 'IP não informado';
    const status = document.createElement('span'); status.setAttribute('role', 'status');
    if (ipv4 && (!port || validPort)) {
      address.href = `http://${ip}${port ? ':' + Number(port) : ''}`;
      address.target = '_blank'; address.rel = 'noopener noreferrer';
      address.title = 'Abrir roteador em outra aba e copiar a senha';
      const password = clientIdentity.equipmentPassword;
      address.onclick = () => {
        if (!password) { status.textContent = 'Senha do roteador não informada no IXC.'; return; }
        copyRouterPassword(password).then(() => { status.textContent = 'Senha do roteador copiada.'; },
          () => { status.textContent = 'Não foi possível copiar a senha. Verifique a permissão do navegador.'; });
      };
    }
    subtitle.replaceChildren(plan, address, status);
  }
  async function loadClientIdentity() {
    const ownGeneration = ++identityGeneration;
    try {
      const logins = await request('/ixc/client?mac=' + encodeURIComponent(selectedClient.mac));
      if (ownGeneration !== identityGeneration || !$('wireless-modal').open) return;
      // Never combine an address and password from different IXC records.
      clientIdentity = logins.length === 1 ? logins[0] : null;
      renderClientIdentity();
    } catch (_) {
      if (ownGeneration !== identityGeneration) return;
      clientIdentity = null; renderClientIdentity();
    }
  }
  let wirelessQualitySamples = [], wirelessQualityNodes = null;
  function wirelessDetails(client) {
    for (const [id, key, unit] of [['wireless-current-signal', 'avg-signal', ' dBm'], ['wireless-current-ccq', 'avg-tx-ccq', '%']]) {
      const raw = client[key];
      $(id).textContent = raw != null && raw !== '' && Number.isFinite(Number(raw)) ? radioNumber(raw) + unit : '—';
    }
    identityRadio = client;
    renderClientIdentity();
    const badge = $('wireless-diagnostic-badge');
    badge.textContent = client.quality || 'Indisponível'; badge.dataset.level = client['quality-severity'] ?? -1;
    $('wireless-diagnostic-reason').textContent = client['quality-primary-reason'] || client['quality-summary'] || 'Dados insuficientes para avaliação.';
    const fragment = document.createDocumentFragment();
    for (const [label, field, unit, metric] of [
      ['Sinal (médio)', 'avg-signal', ' dBm', 'signal'],
      ['SNR (médio)', 'avg-signal-to-noise', ' dB', 'signal-to-noise'],
      ['TX CCQ (médio)', 'avg-tx-ccq', '%', 'tx-ccq'],
      ['P Throughput (kbps)', 'p-throughput', ' kbps', null],
    ]) {
      const card = document.createElement('article'), title = document.createElement('span');
      const value = document.createElement('strong'), state = document.createElement('span');
      const raw = client[field], valid = raw != null && raw !== '' && Number.isFinite(Number(raw))
        && (field !== 'p-throughput' || Number(raw) >= 0);
      title.textContent = label; value.textContent = valid ? radioNumber(raw) + unit : '—';
      const level = valid ? client[metric + '-severity'] : -1;
      state.className = 'wireless-status'; state.dataset.level = level ?? -1;
      state.textContent = signalPolicy.names[level] || 'Indisponível';
      if (valid && metric === 'tx-ccq') {
        if (Number(raw) >= 80) {
          state.textContent = 'Normal';
          state.dataset.level = 0;
        } else {
          state.textContent = signalPolicy.names[level]
            ? 'Avaliado sob tráfego' : 'Não avaliado sob tráfego';
        }
      }
      state.hidden = !metric && valid;
      const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      icon.setAttribute('viewBox', '0 0 24 24'); icon.setAttribute('aria-hidden', 'true');
      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('d', metric === 'signal' ? 'M4 20v-4m5 4v-8m5 8V8m5 12V4'
        : metric === 'signal-to-noise' ? 'M2 12h4l3-9 6 18 3-9h4' : 'M4 19a9 9 0 1 1 16 0M12 13l4-5M8 19h8');
      icon.append(path); title.prepend ? title.prepend(icon) : title.append(icon);
      card.append(title, value, state); fragment.append(card);
    }
    $('wireless-metrics').replaceChildren(fragment);

  }
  function wirelessQualityChart(client, at) {
    if (!Number.isFinite(at) || wirelessQualitySamples.at(-1)?.at === at) return;
    const metric = key => client[key] == null || client[key] === '' ? null : Number(client[key]);
    wirelessQualitySamples.push({ at, signal:metric('avg-signal'), ccq:metric('avg-tx-ccq') });
    wirelessQualitySamples = wirelessQualitySamples.slice(-120);
    if (Object.keys(client).length) $('wireless-updated-at').textContent = new Date(at).toLocaleString('pt-BR');
    const ns = 'http://www.w3.org/2000/svg';
    const node = (tag, attrs = {}, text) => {
      const el = document.createElementNS(ns, tag);
      for (const [key, value] of Object.entries(attrs)) el.setAttribute(key, value);
      if (text != null) el.textContent = text;
      return el;
    };
    if (!wirelessQualityNodes) {
      const svg = node('svg', { viewBox:'0 0 760 220', role:'img', 'aria-label':'Sinal em dBm e TX CCQ em porcentagem ao longo da sessão' });
      const grid = node('g'), lines = [node('path'), node('path')], points = node('g');
      lines.forEach((line, i) => { line.setAttribute('fill','none'); line.setAttribute('stroke',i ? '#4ade80' : '#22d3ee'); line.setAttribute('stroke-width',2); });
      svg.append(grid, ...lines, points); $('wireless-quality-chart').replaceChildren(svg);
      wirelessQualityNodes = { grid, lines, points };
    }
    const samples = wirelessQualitySamples, start = samples[0].at, end = samples.at(-1).at;
    const signals = samples.map(p => p.signal).filter(Number.isFinite);
    const low = Math.min(-90, ...signals.map(v => Math.floor(v / 10) * 10 - 10));
    const high = Math.max(-50, ...signals.map(v => Math.ceil(v / 10) * 10 + 10));
    const grid = [], dots = [];
    for (let i = 0; i <= 4; i++) {
      const y = 25 + i * 40;
      grid.push(node('path', { d:`M60 ${y}H695`, stroke:'#244653', 'stroke-dasharray':'3 4' }), node('text', { x:5, y:y + 4 }, number(high - i / 4 * (high - low)) + ' dBm'), node('text', { x:705, y:y + 4 }, (100 - i * 25) + '%'));
    }
    ['signal','ccq'].forEach((key, index) => {
      let path = '', previous = null;
      samples.forEach(point => {
        if (!Number.isFinite(point[key])) { previous = null; return; }
        const x = 60 + (point.at - start) / Math.max(1, end - start) * 635;
        const y = 185 - (index ? point[key] / 100 : (point[key] - low) / (high - low)) * 160;
        if (previous && point.at - previous.at <= 2500) {
          // Horizontal controls soften transitions without overshooting measured values.
          const middle = (previous.x + x) / 2;
          path += `C${middle},${previous.y} ${middle},${y} ${x},${y} `;
        } else path += `M${x},${y} `;
        previous = { at:point.at, x, y };
        const dot = node('circle', { cx:x, cy:y, r:6, fill:'transparent', stroke:'none', 'pointer-events':'all' });
        dot.append(node('title', {}, `${new Date(point.at).toLocaleTimeString('pt-BR')} · Sinal: ${Number.isFinite(point.signal) ? radioNumber(point.signal) + ' dBm' : '—'} · TX CCQ: ${Number.isFinite(point.ccq) ? radioNumber(point.ccq) + '%' : '—'}`)); dots.push(dot);
      });
      wirelessQualityNodes.lines[index].setAttribute('d',path);
    });
    grid.push(node('text', { x:60, y:213 }, new Date(start).toLocaleTimeString('pt-BR')), node('text', { x:695, y:213, 'text-anchor':'end' }, new Date(end).toLocaleTimeString('pt-BR')));
    wirelessQualityNodes.grid.replaceChildren(...grid); wirelessQualityNodes.points.replaceChildren(...dots);
    $('wireless-quality-range').textContent = `Sinal (dBm) · TX CCQ (%) · ${Math.round((end - start) / 1000)} s disponíveis nesta sessão`;
  }
  let wirelessTrafficNodes = null;
  function wirelessTrafficChart(rates, at, redraw = false) {
    if (!Number.isFinite(at) || (!redraw && wirelessTraffic.at(-1)?.at === at)) return;
    if (!redraw) {
      const previousAt = wirelessTraffic.at(-1)?.at;
      if (previousAt != null && at <= previousAt) return;
      // Fill missed collection intervals without bridging them with measured traffic.
      if (previousAt != null && at - previousAt > 2500) {
        const firstMissingAt = Math.max(previousAt + 1000, at - 119000);
        for (let missingAt = firstMissingAt; missingAt < at; missingAt += 1000) {
          wirelessTraffic.push({ at:missingAt, download:0, upload:0 });
        }
      }
      const value = rate => Number.isFinite(rate) && rate >= 0 ? rate : 0;
      wirelessTraffic.push({ at, download:value(rates.download), upload:value(rates.upload) });
      wirelessTraffic = wirelessTraffic.slice(-120);
    }
    const width = Math.max(300, $('wireless-traffic-chart').getBoundingClientRect?.().width || 760);
    const left = 100, right = width - 20;
    const ns = 'http://www.w3.org/2000/svg';
    const node = (tag, attrs = {}, text) => {
      const el = document.createElementNS(ns, tag);
      for (const [key, value] of Object.entries(attrs)) el.setAttribute(key, value);
      if (text != null) el.textContent = text;
      return el;
    };
    if (!wirelessTrafficNodes) {
      const svg = node('svg', { viewBox:'0 0 760 220', role:'img', 'aria-label':'Download e upload medidos ao longo da sessão' });
      const grid = node('g'), lines = [node('path'), node('path')], points = node('g');
      lines.forEach((line, i) => { line.setAttribute('fill','none'); line.setAttribute('stroke',i ? '#4ade80' : '#22d3ee'); line.setAttribute('stroke-width',2); });
      svg.append(grid, ...lines, points); $('wireless-traffic-chart').replaceChildren(svg);
      wirelessTrafficNodes = { svg, grid, lines, points };
    }
    wirelessTrafficNodes.svg.setAttribute('viewBox', `0 0 ${width} 220`);
    const samples = wirelessTraffic, start = samples[0].at, end = samples.at(-1).at;
    const maximum = Math.max(1000, ...samples.flatMap(p => [p.download, p.upload]).filter(Number.isFinite)) * 1.1;
    const grid = [], dots = [];
    for (let i = 0; i <= 4; i++) {
      const y = 25 + i * 40;
      grid.push(node('path', { d:`M${left} ${y}H${right}`, stroke:'#244653', 'stroke-dasharray':'3 4' }), node('text', { x:5, y:y + 4 }, window.HostTraffic.format(maximum * (1 - i / 4))));
    }
    ['download','upload'].forEach((key, index) => {
      let path = '', previous = null;
      samples.forEach(point => {
        if (!Number.isFinite(point[key])) { previous = null; return; }
        const x = left + (point.at - start) / Math.max(1, end - start) * (right - left);
        const y = 185 - (point[key] / maximum) * 160;
        if (previous) {
          // Horizontal controls soften transitions without overshooting measured values.
          const middle = (previous.x + x) / 2;
          path += `C${middle},${previous.y} ${middle},${y} ${x},${y} `;
        } else path += `M${x},${y} `;
        previous = { at:point.at, x, y };
        const dot = node('circle', { cx:x, cy:y, r:6, fill:'transparent', stroke:'none', 'pointer-events':'all' });
        dot.append(node('title', {}, `${new Date(point.at).toLocaleTimeString('pt-BR')} · Download: ${window.HostTraffic.format(point.download)} · Upload: ${window.HostTraffic.format(point.upload)}`)); dots.push(dot);
      });
      wirelessTrafficNodes.lines[index].setAttribute('d',path);
    });
    grid.push(node('text', { x:left, y:213 }, new Date(start).toLocaleTimeString('pt-BR')), node('text', { x:right, y:213, 'text-anchor':'end' }, new Date(end).toLocaleTimeString('pt-BR')));
    wirelessTrafficNodes.grid.replaceChildren(...grid); wirelessTrafficNodes.points.replaceChildren(...dots);
    $('wireless-traffic-range').textContent = `${Math.round((end - start) / 1000)} s disponíveis nesta sessão`;
  }
  if (typeof ResizeObserver !== 'undefined') {
    const trafficResizeObserver = new ResizeObserver(() => {
      if (wirelessTraffic.length) wirelessTrafficChart({}, wirelessTraffic.at(-1).at, true);
    });
    trafficResizeObserver.observe($('wireless-traffic-chart'));
    window.addEventListener('pagehide', () => trafficResizeObserver.disconnect());
  }
  let clientPingGeneration = 0, clientPingId = null, clientPingTimer, clientPingChart;
  const pingPath = id => '/wireless/ping/' + encodeURIComponent(id);
  function deleteClientPing(id) {
    if (id) fetch(base + pingPath(id), { method:'DELETE', keepalive:true }).catch(() => {});
  }
  function stopClientPing() {
    clientPingGeneration++; clearTimeout(clientPingTimer);
    deleteClientPing(clientPingId); clientPingId = null;
  }
  function showClientPing(view) {
    const samples = view.samples || [];
    if (!samples.length) {
      $('wireless-ping-state').textContent = `IPv4 ${view.ip} · aguardando a primeira resposta do coletor`;
      return;
    }
    const latest = samples.at(-1);
    $('wireless-current-latency').textContent = Number.isFinite(latest.latency) ? number(latest.latency) + ' ms' : '—';
    $('wireless-current-loss').textContent = Number.isFinite(latest.loss) ? number(latest.loss) + '%' : '—';
    const stale = Date.now() - latest.at > 10000;
    $('wireless-ping-state').textContent = `App → ${view.ip} · ${stale ? 'dados desatualizados · ' : ''}última medição: ${new Date(latest.at).toLocaleTimeString('pt-BR')}`
      + (latest.loss == null && latest.latency == null ? ' · falha de coleta (não contabilizada como perda)' : '');
    const series = [];
    samples.forEach((point, index) => {
      const previous = samples[index - 1];
      if (previous && point.at - previous.at > 2500) series.push({ from:previous.at + 1000,
        to:point.at, metrics:{ latency:null, loss:null } });
      series.push({ from:point.at, to:Math.min(point.at + 1000, samples[index + 1]?.at ?? point.at + 1000),
        metrics:{ latency:point.latency, loss:point.loss } });
    });
    clientPingChart?.update({ from:samples[0].at, to:latest.at + 1000, series });
  }
  async function pollClientPing(ownGeneration) {
    if (ownGeneration !== clientPingGeneration || !clientPingId || !$('wireless-modal').open) return;
    try {
      const view = await request(pingPath(clientPingId));
      if (ownGeneration !== clientPingGeneration) return;
      showClientPing(view);
    } catch (error) {
      if (ownGeneration !== clientPingGeneration) return;
      $('wireless-ping-state').textContent = 'Ping indisponível · dados anteriores desatualizados. ' + error.message;
      $('wireless-ping-retry').hidden = false;
      if (error.status === 404) { stopClientPing(); return; }
    }
    if (ownGeneration === clientPingGeneration) clientPingTimer = setTimeout(() => pollClientPing(ownGeneration), 1000);
  }
  async function startClientPing() {
    stopClientPing();
    if (!selectedClient || !$('wireless-modal').open) return;
    const ownGeneration = clientPingGeneration;
    clientPingChart ||= window.HostHistoryCharts.createRealtime?.($('wireless-ping-chart'));
    clientPingChart?.clear('Aguardando medições desta sessão.');
    $('wireless-current-latency').textContent = '—';
    $('wireless-current-loss').textContent = '—';
    $('wireless-ping-retry').hidden = true;
    $('wireless-ping-state').textContent = 'Consultando IPv4 no IXC…';
    try {
      const view = await request('/wireless/ping?mac=' + encodeURIComponent(selectedClient.mac), { method:'POST' });
      if (ownGeneration !== clientPingGeneration || !$('wireless-modal').open) { deleteClientPing(view.id); return; }
      clientPingId = view.id; showClientPing(view); pollClientPing(ownGeneration);
    } catch (error) {
      if (ownGeneration !== clientPingGeneration) return;
      $('wireless-ping-state').textContent = error.message;
      $('wireless-ping-retry').hidden = false;
    }
  }
  $('wireless-ping-retry').onclick = startClientPing;
  window.addEventListener('pagehide', stopClientPing);
  function wirelessBandwidth(client, at) {
    const rates = client['connection-status'] ? {
      download:client['tx-traffic-bps'] == null ? null : Number(client['tx-traffic-bps']),
      upload:client['rx-traffic-bps'] == null ? null : Number(client['rx-traffic-bps']),
    } : window.HostTraffic.wirelessRates(wirelessBaseline, client, at);
    wirelessBaseline = rates.baseline;
    $('wireless-download').textContent = window.HostTraffic.format(rates.download);
    $('wireless-upload').textContent = window.HostTraffic.format(rates.upload);
    const hasCounters = Object.values(window.HostTraffic.wirelessCounters(client)).every(value => /^\d+$/.test(String(value)));
    $('wireless-traffic-state').textContent = rates.download != null || rates.upload != null
      ? ''
      : hasCounters ? 'Aguardando a próxima medição para calcular a velocidade.' : 'O equipamento não informou contadores de tráfego deste cliente.';
    wirelessQualityChart(client, at);
    wirelessTrafficChart(rates, at);
  }
  function resetWirelessBandwidth(message) {
    wirelessBaseline = null;
    wirelessTrafficChart({ download:0, upload:0 }, Math.max(Date.now(), wirelessMeasuredAt || 0));
    $('wireless-download').textContent = 'Indisponível'; $('wireless-upload').textContent = 'Indisponível';
    $('wireless-traffic-state').textContent = message;
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
  const wirelessTooltip = window.HostWirelessTooltip?.create();
  let wirelessSort = { key:null, direction:1 };
  let wirelessFilter = null;
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
    const namedClients = clients.filter(client => wirelessFilter === null || signalPolicy.category(client) === wirelessFilter)
      .map(client => ({ ...client, pppoe:pppoeName(client) }));
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
      tr.setAttribute('aria-label', `${client['mac-address']} · ${client.quality || 'Avaliação indisponível'}${client['connection-status'] === 'disconnected' ? ' · Desconectado' : ''}${client['critical-alert'] ? ' · Crítico agora' : ''}`);
      const keys = ['mac-address', 'pppoe', 'interface', 'signal', 'avg-signal-to-noise', 'avg-tx-ccq', 'p-throughput', 'distance', 'uptime', 'avg-tx-rate', 'avg-rx-rate'];
      keys.forEach((key, index) => {
        const td = document.createElement('td');
        let value = client[key] ?? (key === 'signal' ? client['signal-strength'] : null);
        if (key === 'p-throughput') {
          const throughput = value == null || value === '' ? NaN : Number(value);
          value = Number.isFinite(throughput) && throughput >= 0 ? number(throughput) : null;
        }
        if (key.startsWith('avg-') && value != null && (String(value).trim() === '' || !Number.isFinite(Number(value)))) value = null;
        if (key.startsWith('avg-') && value != null) {
          value = (['avg-signal', 'avg-signal-to-noise', 'avg-tx-ccq'].includes(key) ? tableRadioNumber(value) : number(Number(value))) + (key === 'avg-signal' ? ' dBm' : key === 'avg-signal-to-noise' ? ' dB' : key.endsWith('ccq') ? '%' : ' Mbps');
        }
        value ??= '—';
        td.title = String(value);
        if (index === 0) { const button = document.createElement('button'); button.type = 'button'; button.textContent = value;
          const status = !wirelessAvailable ? 'Dados desatualizados'
            : client['connection-status'] === 'disconnected' ? 'Desconectado' : client.quality || 'Indisponível';
          button.setAttribute('aria-haspopup', 'dialog');
          button.onclick = () => {
            wirelessTooltip?.hide();
            selectedClient = { mac:client['mac-address'], interface:client.interface };
            clientIdentity = null; identityGeneration++;
            wirelessBaseline = null; wirelessTraffic = []; wirelessQualitySamples = []; wirelessQualityNodes = null; wirelessTrafficNodes = null;
            $('wireless-quality-chart').replaceChildren();
            $('wireless-traffic-chart').replaceChildren();
            $('wireless-traffic-range').textContent = 'Aguardando amostras';
            $('wireless-updated-at').textContent = wirelessMeasuredAt ? new Date(wirelessMeasuredAt).toLocaleString('pt-BR') : '—';
            wirelessDetails(client);
            const live = wirelessAvailable && client['connection-status'] !== 'disconnected';
            if (live) wirelessBandwidth(client, wirelessMeasuredAt);
            else resetWirelessBandwidth(status);
            $('wireless-live-state').textContent = live ? 'Dados ao vivo · atualização a cada 1 segundo' : status;
            $('wireless-modal').showModal();
            loadClientIdentity();
            startClientPing();
          }; td.append(button);
        } else if (key === 'signal') {
          const bars = document.createElement('span'); bars.className = 'signal-bars'; bars.setAttribute('aria-hidden', 'true');
          for (let i = 0; i < 4; i++) bars.append(document.createElement('i'));
          td.className = 'signal-cell'; td.append(bars, document.createTextNode(signalPolicy.signal(client) == null ? '—' : tableRadioNumber(signalPolicy.signal(client)) + ' dBm'));
        } else { td.textContent = value; if (key === 'pppoe') td.title = client['radio-name'] || client.comment || client.name || ''; }
        const metric = key === 'signal' ? key : key.startsWith('avg-') ? key.slice(4) : null;
        if (metric !== 'tx-ccq' && metric && client[metric + '-severity'] != null) td.dataset.level = client[metric + '-severity'];
        tr.append(td);
      });
      wirelessTooltip?.bind(tr, client, wirelessAvailable);
      tr.tabIndex = 0; tr.onclick = event => { if (event.pointerType !== 'touch' && event.target.tagName !== 'BUTTON') tr.querySelector('button')?.click(); };
      tr.onkeydown = event => { if (event.target === tr && ['Enter', ' '].includes(event.key)) { event.preventDefault(); tr.querySelector('button')?.click(); } };
      fragment.append(tr);
    }
    replaceRows('wireless-clients', fragment, 'clientMac');
    wirelessTooltip?.update(clients, $('wireless-clients').children, wirelessAvailable);
    if ($('wireless-modal').open && selectedClient) {
      const selected = clients.find(c => c['mac-address'] === selectedClient.mac && c.interface === selectedClient.interface);
      if (wirelessAvailable && selected && selected['connection-status'] !== 'disconnected') {
        wirelessDetails(selected);
        if (typeof measuredAt === 'number') wirelessBandwidth(selected, measuredAt);
        if (typeof measuredAt === 'number') $('wireless-live-state').textContent = 'Atualizado às ' + new Date(measuredAt).toLocaleTimeString('pt-BR');
      } else {
        resetWirelessBandwidth('Cliente desconectado.');
        wirelessQualityChart({}, measuredAt);
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
      layers:'M12 3 2 8l10 5 10-5ZM2 12l10 5 10-5M2 16l10 5 10-5',
      cpu:'M6 6h12v12H6ZM9 1v5m6-5v5M9 18v5m6-5v5M1 9h5m-5 6h5m12-6h5m-5 6h5',
      wds:'M12 9v12m-4 0 4-8 4 8M5 5a10 10 0 0 0 0 12m14-12a10 10 0 0 1 0 12M8 8a5 5 0 0 0 0 6m8-6a5 5 0 0 1 0 6',
      switch:'M3 9h18v11H3Zm4 4v3m5-3v3m5-3v3M7 9V4h10v5',
      other:'M3 3h6v6H3Zm12 12h6v6h-6ZM9 6h9v9M6 9v9h9',
    };
    const kind = /^(wlan|wifi|wireless)/.test(type) ? 'wireless' : Object.hasOwn(paths, type) ? type : 'other';
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    for (const [key, value] of Object.entries({ viewBox:'0 0 24 24', fill:'none', stroke:'currentColor', 'stroke-width':'1.75', 'stroke-linecap':'round', 'stroke-linejoin':'round', 'aria-hidden':'true', class:'interface-type-icon' })) svg.setAttribute(key, value);
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path'); path.setAttribute('d', paths[kind]); svg.append(path);
    return svg;
  }
  let interfaceValidAt = null, interfaceSvg = null;
  function interfaceRate(value) {
    if (!Number.isFinite(value) || value < 0) return '—';
    const unit = Math.min(3, Math.floor(Math.log10(Math.max(1, value)) / 3));
    return (value / 1000 ** unit).toLocaleString('pt-BR', { maximumFractionDigits:2 }) + ' ' + ['bps', 'kbps', 'Mbps', 'Gbps'][unit];
  }
  function interfaceDetails(item) {
    const disabled = item.disabled === 'true' || item.disabled === true;
    const running = item.running === 'true' || item.running === true;
    const state = disabled ? 'Desabilitada' : running ? 'Ativa' : 'Inativa';
    $('interface-modal-title').textContent = item.name;
    $('interface-status').textContent = state;
    $('interface-status').dataset.state = disabled ? 'disabled' : running ? 'active' : 'inactive';
    $('interface-subtitle').textContent = `${item.type || '—'} · ${state}`;
    $('interface-mark').replaceChildren(interfaceIcon(item.type || ''));
    const fragment = document.createDocumentFragment();
    for (const [key, icon] of [['name', 'vlan'], ['type', 'layers'], ['mac-address', 'ether'], ['mtu', 'cpu']]) {
      const card = document.createElement('div'), content = document.createElement('div');
      const label = document.createElement('dt'), value = document.createElement('dd');
      label.textContent = labels[key]; value.textContent = item[key] || '—';
      content.append(label, value); card.append(interfaceIcon(icon), content); fragment.append(card);
    }
    $('interface-details').replaceChildren(fragment);
  }
  function interfaceGap() {
    const at = Date.now();
    if (!traffic.length || at <= traffic.at(-1).at) return;
    traffic = [...traffic, { at, rx:null, tx:null }].slice(-120);
    interfaceChart();
  }
  function interfaceChart() {
    const container = $('traffic-chart'), ns = 'http://www.w3.org/2000/svg';
    if (!interfaceSvg) {
      interfaceSvg = document.createElementNS(ns, 'svg');
      interfaceSvg.setAttribute('viewBox', '0 0 760 220');
      interfaceSvg.setAttribute('role', 'img');
      interfaceSvg.setAttribute('aria-label', 'Download RX e Upload TX ao longo do tempo');
      container.replaceChildren(interfaceSvg);
    }
    const width = Math.max(300, container.getBoundingClientRect?.().width || 760);
    const left = 100, right = width - 20;
    interfaceSvg.setAttribute('viewBox', `0 0 ${width} 220`);
    interfaceSvg.replaceChildren();
    const node = (tag, attributes, text) => {
      const el = document.createElementNS(ns, tag);
      for (const [key, value] of Object.entries(attributes)) el.setAttribute(key, value);
      if (text != null) el.textContent = text;
      interfaceSvg.append(el); return el;
    };
    const values = traffic.flatMap(p => [p.rx, p.tx]).filter(Number.isFinite);
    const ceiling = Math.max(1, ...values) * 1.1;
    const magnitude = 10 ** Math.floor(Math.log10(ceiling));
    const max = Math.ceil(ceiling / magnitude) * magnitude;
    const first = traffic[0]?.at ?? 0, last = traffic.at(-1)?.at ?? first;
    const x = at => 100 + (at - first) / Math.max(1, last - first) * (right - left);
    const y = value => 185 - value / max * 160;
    for (let i = 0; i <= 4; i++) {
      const value = max * i / 4;
      node('path', { d:`M100 ${y(value)}H${right}`, class:'interface-grid' });
      node('text', { x:5, y:y(value) + 4 }, interfaceRate(value));
    }
    const time = at => new Date(at).toLocaleTimeString('pt-BR');
    if (traffic.length) {
      node('text', { x:left, y:213 }, time(first));
      node('text', { x:right, y:213, 'text-anchor':'end' }, time(last));
    }
    for (const [key, color] of [['rx', '#22d3ee'], ['tx', '#4ade80']]) {
      let path = '', previous = null;
      traffic.forEach(p => {
        if (!Number.isFinite(p[key])) { previous = null; return; }
        const px = x(p.at), py = y(p[key]);
        if (previous) {
          const middle = (previous.x + px) / 2;
          path += `C${middle},${previous.y} ${middle},${py} ${px},${py} `;
        } else path += `M${px},${py} `;
        previous = { x:px, y:py };
        const dot = node('circle', { cx:px, cy:py, r:6, fill:'transparent', stroke:'none', 'pointer-events':'all' });
        const title = document.createElementNS(ns, 'title');
        title.textContent = `${time(p.at)} · Download RX: ${interfaceRate(p.rx)} · Upload TX: ${interfaceRate(p.tx)}`;
        dot.append(title);
      });
      node('path', { d:path, fill:'none', stroke:color, 'stroke-width':2 });
    }
    let tooltip = $('interface-traffic-tooltip');
    if (!tooltip) {
      tooltip = document.createElement('div'); tooltip.id = 'interface-traffic-tooltip';
      tooltip.className = 'diagnostic-chart-tooltip'; tooltip.hidden = true; container.append(tooltip);
    }
    const inspect = index => {
      const point = traffic[index]; if (!point) return;
      tooltip.textContent = `${time(point.at)} · Download RX: ${interfaceRate(point.rx)} · Upload TX: ${interfaceRate(point.tx)}`;
      tooltip.hidden = false; tooltip.style.left = '104px';
    };
    interfaceSvg.onmousemove = event => {
      const bounds = interfaceSvg.getBoundingClientRect();
      const at = first + Math.max(0, Math.min(1, ((event.clientX - bounds.left) / bounds.width * width - left) / (right - left))) * (last - first);
      const index = traffic.reduce((best, p, i) => Math.abs(p.at - at) < Math.abs(traffic[best].at - at) ? i : best, 0);
      inspect(index);
    };
    interfaceSvg.onmouseleave = () => { tooltip.hidden = true; };
    interfaceSvg.setAttribute('tabindex', '0');
    let cursor = traffic.length - 1;
    interfaceSvg.onkeydown = event => {
      if (!['ArrowLeft', 'ArrowRight', 'Escape'].includes(event.key)) return;
      event.preventDefault();
      if (event.key === 'Escape') { tooltip.hidden = true; return; }
      cursor = Math.max(0, Math.min(traffic.length - 1, cursor + (event.key === 'ArrowLeft' ? -1 : 1))); inspect(cursor);
    };
    interfaceSvg.onblur = () => { tooltip.hidden = true; };
    $('interface-download').textContent = interfaceRate(traffic.at(-1)?.rx);
    $('interface-upload').textContent = interfaceRate(traffic.at(-1)?.tx);
  }
  if (typeof ResizeObserver !== 'undefined') {
    const interfaceResizeObserver = new ResizeObserver(() => {
      if ($('interface-modal').open && interfaceSvg) interfaceChart();
    });
    interfaceResizeObserver.observe($('traffic-chart'));
    window.addEventListener('pagehide', () => interfaceResizeObserver.disconnect());
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
        interfaceDetails(item);
        interfaceValidAt = null; interfaceSvg = null;
        $('interface-updated-at').textContent = 'Aguardando leitura';
        $('traffic-chart').replaceChildren();
        interfaceChart();
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
  function liveModalOpen() {
    return $('wireless-modal').open || $('interface-modal').open;
  }
  function livePollingEnabled() {
    return !document.hidden || liveModalOpen();
  }
  async function poll() {
    if (!livePollingEnabled()) return;
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
          details('resource-details', { ...resource, frequency:resource.channel || resource.frequency }, ['identity','board-name','version','uptime','cpu-load','free-memory','total-memory','frequency','ssid']);
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
        if (wirelessAvailable) {
          clients = wirelessResult.rows;
          wirelessMeasuredAt = wirelessResult.measuredAt;
        }

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
        wireless(result.warning ? undefined : result.measuredAt);
        if (result.warning && $('wireless-modal').open) $('wireless-live-state').textContent = 'Dados desatualizados';
        $('wireless-state').textContent = result.warning
          ? result.warning + ' · últimos dados desatualizados · leitura válida: ' + (lastValidAt ? new Date(lastValidAt).toLocaleTimeString('pt-BR') : '—')
          : 'TX CCQ exibe a média geral. Consulte o diagnóstico da linha para ver o CCQ sob tráfego.';
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
          interfaceGap();
        }
        if ($('interface-modal').open && selected) interfaceDetails(selected);
        if ($('interface-modal').open && selected) {
          const sample = await request('/traffic?selectedInterface=' + encodeURIComponent(selectedInterface), { signal:controller.signal });
          if (generation !== ownGeneration) return;
          const row = sample.rows[0];
          const rate = key => row?.[key] != null && String(row[key]).trim() !== '' && Number.isFinite(Number(row[key])) && Number(row[key]) >= 0 ? Number(row[key]) : null;
          const rx = rate('rx-bits-per-second'), tx = rate('tx-bits-per-second');
          if (!sample.warning && Number.isFinite(sample.measuredAt) && (rx != null || tx != null)) {
            if (interfaceValidAt == null || sample.measuredAt > interfaceValidAt) {
              interfaceValidAt = sample.measuredAt;
              traffic.push({ at:sample.measuredAt, rx, tx });
              traffic = traffic.slice(-120);
              interfaceChart();
            }
            $('interface-updated-at').textContent = 'Atualizado às ' + new Date(interfaceValidAt).toLocaleTimeString('pt-BR');
            $('interface-live-state').textContent = '';
          } else {
            $('interface-live-state').textContent = 'Leitura indisponível · dados desatualizados';
            interfaceGap();
          }
        }
      }
      if (overviewError) throw overviewError;
      document.querySelectorAll('[data-tab]').forEach(b => { b.textContent = b.dataset.tab === 'wireless' ? `Clientes wireless (${wirelessAvailable ? signalPolicy.summarize(clients).total : '—'})` : `Interfaces (${interfacesAvailable ? interfaces.length : '—'})`; });
      if (!result.warning) lastValidAt = result.measuredAt;
      root.dataset.api = 'online';
      equipmentState();
      $('equipment-api').textContent = 'API conectada';
      $('host-updated').textContent = 'Última leitura válida: ' + (lastValidAt ? new Date(lastValidAt).toLocaleString('pt-BR') : '—');
      $('live-error').hidden = true;
    } catch (error) {
      if (error.name !== 'AbortError' && generation === ownGeneration) {
        if ($('wireless-modal').open) { resetWirelessBandwidth('Consulta de tráfego indisponível.'); $('wireless-live-state').textContent = 'Atualização indisponível · dados anteriores podem estar desatualizados'; }
        if ($('interface-modal').open) $('interface-live-state').textContent = 'Atualização indisponível · dados anteriores podem estar desatualizados';
        if (tab === 'wireless') {
          if (!wirelessAvailable) wireless();
          $('wireless-state').textContent = 'Falha na comunicação com o AP · dados desatualizados · últimos valores preservados';
        }
        if ($('interface-modal').open) interfaceGap();
        root.dataset.api = 'unavailable';
        equipmentState();
        $('equipment-api').textContent = 'API desconectada';
        $('live-error').textContent = error.message; $('live-error').hidden = false;
      }
    } finally { if (generation === ownGeneration && livePollingEnabled()) liveTimer = setTimeout(poll, 1000); }
  }
  function restart() { generation++; controller?.abort(); clearTimeout(liveTimer); poll(); }
  document.querySelectorAll('[data-tab]').forEach(button => button.onclick = () => {
    initialTab = false;
    interfaceCounters.clear();
    tab = button.dataset.tab;
    if (tab !== 'wireless') wirelessTooltip?.hide();
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
    identityGeneration++; clientIdentity = null; identityRadio = null;
    $('wireless-modal-title').textContent = '';
    $('wireless-modal-subtitle').replaceChildren();
    stopClientPing();
    clientPingChart?.clear('Aguardando medições desta sessão.');
    const previous = selectedClient;
    selectedClient = null; wirelessBaseline = null; wirelessTraffic = [];
    const row = Array.from($('wireless-clients').children).find(item => item.dataset.clientMac === previous?.mac && item.dataset.clientInterface === previous?.interface);
    row?.querySelector('button')?.focus({ preventScroll:true });
    restart();
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
  document.addEventListener('visibilitychange', () => {
    if (liveModalOpen()) return;
    if (document.hidden) {
      interfaceCounters.clear(); generation++; controller?.abort(); clearTimeout(liveTimer);
    } else restart();
  });
  window.addEventListener('pagehide', () => { generation++; controller?.abort(); historyController?.abort(); clearTimeout(liveTimer); clearInterval(historyTimer); });
  historyTimer = setInterval(() => { if (!document.hidden) history(true); }, 60000);
  history(); poll();
})();
