(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const root = document.querySelector('[data-datacom-host]');
  const base = '/api/hosts/' + encodeURIComponent(root.dataset.datacomHost) + '/datacom/';
  let busy = false, timer, overviewAt = 0, previous = new Map(), samples = [], overviewWarning = '';
  const text = value => value == null || value === '' ? '—' : value;
  async function read(section) {
    const response = await fetch(base + section, { cache: 'no-store', signal: AbortSignal.timeout(65000) });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || 'Consulta indisponível.');
    return result;
  }
  function draw() {
    const container = $('datacom-chart');
    container.replaceChildren();
    if (samples.length < 2) return;
    const ns = 'http://www.w3.org/2000/svg', width = 800, height = 220;
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('width', '100%'); svg.setAttribute('height', String(height));
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    svg.setAttribute('role', 'img'); svg.setAttribute('aria-label', 'Tráfego RX em azul e TX em verde. Valores atuais acima do gráfico.');
    const max = Math.max(1, ...samples.flatMap(p => [p.rx || 0, p.tx || 0]));
    const x = at => 100 + (at - samples[0].at) / Math.max(1, samples.at(-1).at - samples[0].at) * 685;
    const y = value => 195 - value / max * 175;
    for (const [key, color] of [['rx', 'var(--focus)'], ['tx', 'var(--success, #22c55e)']]) {
      const path = document.createElementNS(ns, 'path');
      path.setAttribute('d', window.AvailabilityChart.paths(samples.map(p => ({ ...p, from: p.at, to: p.at })), key, x, y, 195).line);
      path.setAttribute('fill', 'none'); path.setAttribute('stroke', color); path.setAttribute('stroke-width', '2');
      svg.append(path);
    }
    for (const ratio of [0, 0.5, 1]) {
      const label = document.createElementNS(ns, 'text');
      label.setAttribute('x', '95'); label.setAttribute('y', String(y(max * ratio) + 4));
      label.setAttribute('text-anchor', 'end'); label.setAttribute('fill', 'var(--muted)'); label.setAttribute('font-size', '11');
      label.textContent = window.HostTraffic.format(max * ratio); svg.append(label);
    }
    for (const [at, anchor] of [[samples[0].at, 'start'], [samples.at(-1).at, 'end']]) {
      const label = document.createElementNS(ns, 'text');
      label.setAttribute('x', String(x(at))); label.setAttribute('y', '216');
      label.setAttribute('text-anchor', anchor); label.setAttribute('fill', 'var(--muted)'); label.setAttribute('font-size', '11');
      label.textContent = new Date(at).toLocaleTimeString('pt-BR'); svg.append(label);
    }
    container.append(svg);
  }
  $('datacom-interface').addEventListener('change', () => { samples = []; draw(); schedule(0); });
  function schedule(delay = 10000) { clearTimeout(timer); timer = setTimeout(poll, delay); }
  async function poll() {
    if (busy || document.hidden) return;
    busy = true; $('datacom-refresh').disabled = true;
    try {

      if (Date.now() - overviewAt > 30000) {
        const result = await read('overview'), row = result.rows[0] || {};
        for (const [id, key] of [['model', 'board-name'], ['version', 'version'], ['uptime', 'uptime']]) $('datacom-' + id).textContent = text(row[key]);
        $('datacom-cpu').textContent = row['cpu-load'] == null ? '—' : row['cpu-load'] + '%';
        const memory = value => value == null ? '—' : (Number(value) / 1048576).toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + ' MiB';
        $('datacom-memory').textContent = memory(row['free-memory']) + ' / ' + memory(row['total-memory']);
        overviewAt = Date.now(); overviewWarning = result.warning || '';
      }
      const result = await read('interfaces'), next = new Map(), body = $('datacom-interfaces');
      const select = $('datacom-interface'), selected = select.value, focused = document.activeElement === select;
      body.replaceChildren();
      const rates = new Map();
      for (const row of result.rows) {
        const before = previous.get(row.name), elapsed = result.measuredAt - (before?.at || result.measuredAt);
        const rx = window.HostTraffic.rate(before?.['rx-byte'], row['rx-byte'], elapsed);
        const tx = window.HostTraffic.rate(before?.['tx-byte'], row['tx-byte'], elapsed);
        rates.set(row.name, { rx, tx }); next.set(row.name, { ...row, at: result.measuredAt });
        const tr = document.createElement('tr');
        const state = row.disabled === 'true' ? 'Desativada' : row.running === 'true' ? 'UP' : row.running === 'false' ? 'DOWN' : 'Indisponível';
        for (const value of [row.name, state, row.rate, row.mtu, row['mac-address'], window.HostTraffic.format(rx), window.HostTraffic.format(tx), text(row['rx-error']) + ' / ' + text(row['tx-error'])]) {
          const td = document.createElement('td'); td.textContent = text(value); tr.append(td);
        }
        body.append(tr);
      }
      const names = result.rows.map(row => row.name);
      if (names.join('\n') !== [...select.options].slice(1).map(o => o.value).join('\n')) {
        select.replaceChildren(new Option('Selecione uma interface', ''), ...names.map(name => new Option(name, name)));
        select.value = names.includes(selected) ? selected : '';
        if (focused) select.focus();
      }
      previous = next;
      const rate = rates.get(select.value);
      if (rate) {
        samples.push({ at: result.measuredAt, ...rate }); samples = samples.slice(-120); draw();
        $('datacom-traffic-state').textContent = `RX: ${window.HostTraffic.format(rate.rx)} · TX: ${window.HostTraffic.format(rate.tx)}`;
      } else { samples = []; draw(); $('datacom-traffic-state').textContent = 'Selecione uma interface para acompanhar o tráfego.'; }
      $('datacom-state').textContent = 'Última leitura válida: ' + new Date(result.measuredAt).toLocaleString('pt-BR') + ([overviewWarning, result.warning].filter(Boolean).length ? ' · ' + [overviewWarning, result.warning].filter(Boolean).join(' ') : '');
    } catch (error) {
      previous.clear(); samples.push({ at: Date.now(), rx: null, tx: null }); samples = samples.slice(-120); draw();
      $('datacom-state').textContent = error.message + ' Dados anteriores desatualizados.';
      $('datacom-traffic-state').textContent = 'Tráfego indisponível · aguardando novas leituras.';
    } finally { busy = false; $('datacom-refresh').disabled = false; schedule(); }
  }
  $('datacom-refresh').addEventListener('click', () => { overviewAt = 0; schedule(0); });
  document.addEventListener('visibilitychange', () => { clearTimeout(timer); if (!document.hidden) schedule(0); });
  poll();
})();
