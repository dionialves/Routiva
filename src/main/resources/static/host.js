(() => {
  'use strict';
  let report = window.hostReport;
  const period = document.getElementById('hours');
  const selection = document.getElementById('minute-selection');
  const status = document.getElementById('refresh-status');
  const ns = 'http://www.w3.org/2000/svg';
  const observers = [];
  const cursors = [];
  let renderedPeriod = period.value;
  let inFlight = false;
  let selectionGeneration = 0;
  let requestError = false;
  const number = (value) => value == null ? 'Indefinida' : new Intl.NumberFormat('pt-BR', {
    minimumFractionDigits: 2, maximumFractionDigits: 4,
  }).format(value);
  const time = (value) => new Date(value).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
  const percent = (value) => number(value) + (value == null ? '' : '%');

  const loading = () => report.load != null && report.load.phase !== 'COMPLETE';
  const uncovered = (point) => loading() && (report.load.loadedFrom == null || point.from < report.load.loadedFrom);

  function freshness() {
    status.classList.toggle('loss-key', requestError ||
      (report.evaluatedAt != null && Date.now() - report.evaluatedAt > 30000));
    if (requestError) status.textContent = 'Falha ao consultar. Mantendo a última publicação; nova tentativa em 10 segundos.';
    else if (renderedPeriod !== period.value) status.textContent = 'Carregando o período selecionado…';
    else if (report.evaluatedAt == null) status.textContent = 'Carregando indicadores…';
    else status.textContent = `${Date.now() - report.evaluatedAt > 30000 ? 'Publicação desatualizada · ' : ''}` +
      `${loading() ? 'Dados parciais · Histórico em carregamento · ' : ''}` +
      `Indicadores calculados às ${time(report.evaluatedAt)} · Última medição: ${report.lastSampleAt == null ? 'sem dados' : time(report.lastSampleAt)} · Consulta a cada 10 segundos`;
  }

  function element(name, attrs, text) {
    const el = document.createElementNS(ns, name);
    for (const [key, value] of Object.entries(attrs)) el.setAttribute(key, value);
    if (text != null) el.textContent = text;
    return el;
  }

  function describe(point) {
    const m = point.metrics;
    return `${time(point.from)} — ${time(point.to)}` +
      `${uncovered(point) ? ' · Histórico ainda não carregado integralmente; valores parciais' : ''}` +
      `${point.provisional ? ' · minuto em andamento' : point.partial ? ' · intervalo parcial' : ''}` +
      ` · ${m.healthy} saudáveis, ${m.degraded} degradados, ${m.lost} perdidos, ${m.errors} erros internos` +
      ` · Operacional: ${percent(m.operational)} · Técnica: ${percent(m.technical)}` +
      ` · Perda: ${percent(m.loss)} · Latência média: ${m.latency == null ? 'sem resposta' : number(m.latency) + ' ms'}` +
      (m.healthy + m.degraded + m.lost === 0 ? ' · Sem dados elegíveis' : ' · As contagens não garantem coleta integral no minuto');
  }

  function selectMinute(index) {
    if (!report.series.length) return;
    index = Math.max(0, Math.min(report.series.length - 1, index));
    selection.value = String(index);
    const point = report.series[index];
    document.getElementById('minute-detail').textContent = describe(point);
    selection.setAttribute('aria-valuetext', describe(point));
    for (const update of cursors) update(point);
  }
  selection.addEventListener('input', () => selectMinute(Number(selection.value)));

  function chart(id, lines, unit, fixedMax) {
    const current = report;
    const container = document.getElementById(id);
    const svg = element('svg', {
      viewBox: '0 0 800 290', role: 'img',
      'aria-label': `${container.parentElement.querySelector('h2').textContent}. Explore os minutos pelo controle abaixo dos gráficos.`,
    });
    const max = fixedMax || Math.max(1, ...current.series.map((p) => p.metrics.latency || 0)) * 1.1;
    const x = (point) => 65 + (((point.from + point.to) / 2 - current.from) / (current.to - current.from)) * 710;
    const y = (value) => 210 - (value / max) * 180;
    for (let i = 0; i <= 4; i++) {
      const value = (max * i) / 4;
      svg.append(
        element('line', { x1: 65, x2: 775, y1: y(value), y2: y(value), stroke: '#2c394e' }),
        element('text', { x: 55, y: y(value) + 4, 'text-anchor': 'end', fill: '#97a5bd', 'font-size': 12 },
          `${new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 2 }).format(value)}${unit}`),
      );
    }
    for (const [key, color] of lines) {
      let path = '';
      let connected = false;
      current.series.forEach((point, i) => {
        const value = point.metrics[key];
        if (value == null) { connected = false; return; }
        path += `${connected ? 'L' : 'M'}${x(point)},${y(value)} `;
        // A standalone valid minute must remain visible even without a line segment.
        if (!connected && current.series[i + 1]?.metrics[key] == null)
          svg.append(element('circle', { cx: x(point), cy: y(value), r: 2, fill: color }));
        connected = true;
      });
      svg.append(element('path', { d: path, fill: 'none', stroke: color, 'stroke-width': 2 }));
    }
    if (id === 'latency-chart') {
      let losses = '', missing = '';
      for (const point of current.series) {
        const segment = `M${x(point)},222 v8 `;
        if (point.metrics.lost > 0) losses += segment;
        else if (point.metrics.technical == null) missing += segment;
      }
      svg.append(
        element('path', { d: missing, stroke: '#97a5bd', 'stroke-width': 2, 'aria-label': 'Minutos sem dados elegíveis' }),
        element('path', { d: losses, stroke: '#ff7b85', 'stroke-width': 3, 'aria-label': 'Minutos com perda de pacotes' }),
      );
    }
    const cursor = element('line', { x1: 65, x2: 65, y1: 25, y2: 232, stroke: '#ffffff', 'stroke-opacity': 0.5 });
    svg.append(cursor);
    cursors.push((point) => { cursor.setAttribute('x1', x(point)); cursor.setAttribute('x2', x(point)); });
    const axis = element('g', { 'aria-label': 'Horários do período' });
    svg.append(axis);
    container.replaceChildren(svg);
    function renderTimes() {
      axis.replaceChildren();
      const duration = current.to - current.from;
      const scale = svg.getBoundingClientRect().width / 800;
      if (!scale || duration <= 0) return;
      const spacing = Math.max(46, 52 / scale);
      const intervals = [5, 10, 15, 30, 60, 120, 240, 360, 480, 720, 1440];
      const step = (intervals.find((v) => ((v * 60000) / duration) * 710 >= spacing) || 1440) * 60000;
      function label(at, position, anchor) {
        axis.append(element('text', { x: position, y: 262, 'text-anchor': anchor, fill: '#97a5bd', 'font-size': 12 },
          new Date(at).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })));
      }
      label(current.from, 65, 'start');
      for (let at = Math.ceil(current.from / step) * step; at < current.to; at += step) {
        const position = 65 + ((at - current.from) / duration) * 710;
        if (position - 65 >= spacing * 1.5 && 775 - position >= spacing * 1.5) label(at, position, 'middle');
      }
      label(current.to, 775, 'end');
    }
    renderTimes();
    const observer = new ResizeObserver(renderTimes);
    observer.observe(container);
    observers.push(observer);
    svg.addEventListener('click', (event) => {
      const rect = svg.getBoundingClientRect();
      if (!rect.width || !current.series.length) return;
      const fraction = Math.max(0, Math.min(1, ((event.clientX - rect.left) / rect.width * 800 - 65) / 710));
      const at = current.from + fraction * (current.to - current.from);
      const index = current.series.findIndex((point) => at < point.to);
      selectMinute(index < 0 ? current.series.length - 1 : index);
      selection.focus();
    });
  }

  function render(selectedAt = null) {
    for (const observer of observers.splice(0)) observer.disconnect();
    cursors.length = 0;
    for (const key of ['operational', 'technical', 'loss', 'degradation'])
      document.getElementById(key).textContent = percent(report.total[key]);
    document.getElementById('counts').textContent =
      `${report.total.healthy} saudáveis · ${report.total.degraded} degradados · ${report.total.lost} perdidos · ${report.total.errors} erros internos`;
    document.getElementById('no-data').hidden = loading() || report.evaluatedAt == null || report.total.technical != null;
    document.getElementById('date-range').textContent = report.evaluatedAt == null ? '' : `${time(report.from)} — ${time(report.to)}`;
    document.getElementById('retention-note').textContent = report.evaluatedAt != null && report.retainedFrom > report.from
      ? `Retenção limitada: somente dados a partir de ${time(report.retainedFrom)} podem compor este período.` : '';
    if (loading()) document.getElementById('retention-note').textContent +=
      ' Atualizando registros recentes. Os valores ainda são parciais.';
    for (const id of ['availability-chart', 'loss-chart', 'latency-chart'])
      document.getElementById(id).hidden = report.evaluatedAt == null;
    if (report.evaluatedAt == null) {
      selection.disabled = true;
      document.getElementById('minute-detail').textContent = '';
      freshness();
      return;
    }
    chart('availability-chart', [['operational', '#4ee4b1'], ['technical', '#7dafff']], '%', 100);
    chart('loss-chart', [['loss', '#ff7b85'], ['degradation', '#ffd166']], '%', 100);
    chart('latency-chart', [['latency', '#7dafff']], '', null);
    selection.max = String(Math.max(0, report.series.length - 1));
    selection.disabled = report.evaluatedAt == null;
    const selectedIndex = selectedAt == null ? -1 : report.series.findIndex((point) => point.at === selectedAt);
    selectMinute(selectedIndex < 0 ? report.series.length - 1 : selectedIndex);
    freshness();
  }

  async function refresh() {
    if (inFlight) return;
    inFlight = true;
    const generation = selectionGeneration;
    const hours = period.value;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 9000);
    try {
      const response = await fetch(`/api/hosts/${encodeURIComponent(window.hostId)}/availability?hours=${encodeURIComponent(hours)}`, {
        cache: 'no-store', signal: controller.signal,
      });
      if (!response.ok) throw new Error('Falha na consulta');
      const next = await response.json();
      if (generation !== selectionGeneration) return;
      requestError = false;
      if (next.version !== report.version || renderedPeriod !== hours) {
        const selectedIndex = Number(selection.value);
        const selectedAt = renderedPeriod === hours && selectedIndex < report.series.length - 1
          ? report.series[selectedIndex]?.at : null;
        report = next;
        renderedPeriod = hours;
        render(selectedAt);
      }
    } catch (error) {
      if (generation === selectionGeneration) requestError = true;
    } finally {
      clearTimeout(timeout);
      inFlight = false;
      freshness();
      if (generation !== selectionGeneration) refresh();
    }
  }
  period.addEventListener('change', () => {
    selectionGeneration++;
    const url = new URL(window.location.href);
    url.searchParams.set('hours', period.value);
    window.history.replaceState(null, '', url);
    freshness();
    refresh();
  });
  period.form.addEventListener('submit', (event) => { event.preventDefault(); refresh(); });
  render();
  setInterval(refresh, 10000);
})();
