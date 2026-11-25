'use strict';

const MapConnectionTooltip = (() => {
  function details(reading, definition, hosts, failed = false, now = Date.now(), aggregatePon = false) {
    const endpoints = ['a', 'b'].map((key, index) => {
      const sample = reading?.[key];
      const hostId = (index ? reading?.hostB : reading?.hostA) || (index ? definition?.hostB : definition?.hostA);
      const fresh = MapTraffic.fresh(sample, now);
      const valid = fresh && !sample.error && typeof sample.up === 'boolean';
      return {name: hosts.find(host => host.id === hostId)?.name || hostId || 'Equipamento indisponível',
        interface: sample?.name || (index ? definition?.interfaceB : definition?.interfaceA) || 'Interface indisponível',
        status: valid ? (sample.up ? 'UP' : 'DOWN') : 'Sem dados',
        note: sample?.error || (sample && !fresh ? 'Leitura desatualizada' : ''), measuredAt: sample?.measuredAt};
    });
    const status = endpoints.some(e => e.status === 'DOWN') ? 'Inativa'
      : endpoints.every(e => e.status === 'UP') ? 'Ativa' : 'Indisponível';
    const traffic = MapTraffic.state(aggregatePon && reading ? {a: reading.a} : reading, now);
    const staleTraffic = reading?.a && !reading.a.error && (reading.a.rxBps != null && now > reading.a.rxValidUntil
      || reading.a.txBps != null && now > reading.a.txValidUntil);
    const timestamps = endpoints.map(e => e.measuredAt).filter(t => Number.isFinite(t) && t > 0 && t <= now);
    const queried = [reading?.a?.queriedAt, reading?.b?.queriedAt].filter(t => Number.isFinite(t) && t > 0);
    return {endpoints, status, traffic, aggregatePon, queriedAt: queried.length ? Math.min(...queried) : null, measuredAt: timestamps.length ? Math.min(...timestamps) : null,
      note: failed ? 'Consulta indisponível · últimos dados preservados' : reading?.a?.queryError || reading?.b?.queryError || reading?.a?.trafficError || (staleTraffic ? 'Tráfego: leitura desatualizada' : '') || (!reading ? 'Carregando dados do Zabbix' : '')};
  }
  let panel, activeId, activeSource, lastPoint, openTimer, pending, watchingDismissal = false;
  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }
  function icon(name) {
    const node = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    node.setAttribute('viewBox', '0 0 24 24'); node.setAttribute('aria-hidden', 'true');
    const paths = {
      network: 'M10 3h4v5h-4z M12 8v5 M5 16v-3h14v3 M2 16h6v5H2z M16 16h6v5h-6z',
      'network-device': 'M3 6h18a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z M6 13h.1 M10 13h.1 M14 13h.1 M18 13h.1',
    };
    if (paths[name]) {
      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('d', paths[name]); node.append(path); return node;
    }
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', '/icons.svg#' + name); node.append(use);
    return node;
  }
  function badge(text, state) { return element('span', 'connection-tooltip-badge ' + (state || ''), text); }
  function render(data) {
    const header = element('header', 'connection-tooltip-heading');
    header.append(icon('network'), element('strong', '', 'Detalhes da conexão'),
      badge(data.status, data.status === 'Inativa' ? 'is-down' : data.status === 'Ativa' ? 'is-active' : ''));
    const body = element('section', 'connection-tooltip-endpoints');
    data.endpoints.forEach((endpoint, index) => {
      if (index) { const bridge = element('div', 'connection-tooltip-bridge'); bridge.append(icon('arrow-down-up')); body.append(bridge); }
      const block = element('div', 'connection-tooltip-endpoint');
      const heading = element('div', 'connection-tooltip-row');
      heading.append(element('span', 'connection-tooltip-label', index ? 'DESTINO · B' : 'ORIGEM · A'),
        badge(endpoint.status, endpoint.status === 'DOWN' ? 'is-down' : endpoint.status === 'UP' ? 'is-up' : ''));
      const name = element('strong', 'connection-tooltip-host', endpoint.name);
      name.setAttribute('title', endpoint.name);
      const port = element('div', 'connection-tooltip-port');
      port.setAttribute('title', endpoint.interface); port.append(icon('plug'), element('span', '', endpoint.interface));
      const device = element('span', 'connection-tooltip-device'); device.append(icon('network-device'));
      const content = element('div', 'connection-tooltip-device-content');
      content.append(heading, name, port); block.append(device, content);
      if (endpoint.note) block.append(element('p', 'connection-tooltip-note', endpoint.note));
      body.append(block);
    });
    const traffic = element('section', 'connection-tooltip-traffic');
    const heading = element('div', 'connection-tooltip-row');
    const trafficTitle = element('strong', 'connection-tooltip-traffic-title');
    trafficTitle.append(icon('chart-column'), element('span', '', data.aggregatePon ? 'Tráfego da porta PON' : 'Tráfego atual'));
    heading.append(trafficTitle,
      badge(data.traffic.capacity > 0 ? MapTraffic.rate(data.traffic.capacity) : 'Capacidade indisponível'));
    traffic.append(heading);
    ['tx', 'rx'].forEach((key, index) => {
      const row = element('div', 'connection-tooltip-rate ' + key);
      const line = element('div', 'connection-tooltip-rate-line');
      const label = element('span', 'connection-tooltip-direction ' + key);
      label.append(icon(index ? 'arrow-left' : 'arrow-right'), element('span', '', data.aggregatePon ? (index ? 'RX total' : 'TX total') : (index ? 'RX · B → A' : 'TX · A → B')));
      const value = data.traffic[key];
      const utilization = value != null && data.traffic.capacity > 0 ? value / data.traffic.capacity * 100 : null;
      const valueBlock = element('strong', 'connection-tooltip-value', MapTraffic.rate(value));
      const percent = element('span', 'connection-tooltip-percent', utilization == null ? '—' :
        utilization.toLocaleString('pt-BR', {maximumFractionDigits: 2}) + '%');
      line.append(label, valueBlock, percent);
      const track = element('div', 'connection-tooltip-track');
      track.setAttribute('role', 'img');
      track.setAttribute('aria-label', utilization == null ? 'Utilização indisponível' : 'Utilização: ' + percent.textContent);
      const fill = element('span', 'connection-tooltip-fill');
      fill.style.width = (utilization == null ? 0 : Math.min(100, Math.max(0, utilization))) + '%';
      track.append(fill); row.append(line, track); traffic.append(row);
    });
    const footer = element('footer', 'connection-tooltip-row connection-tooltip-footer');
    const label = element('span', 'connection-tooltip-direction'); label.append(icon('clock-3'), element('span', '', 'Última coleta'));
    footer.append(label, element('span', '', data.measuredAt ? new Date(data.measuredAt).toLocaleString('pt-BR') : 'Indisponível'));
    panel.replaceChildren(header, body, traffic, footer);
    if (data.queriedAt) {
      const queryFooter = element('footer', 'connection-tooltip-row connection-tooltip-footer');
      queryFooter.append(element('span', '', 'Consulta ao Zabbix'),
        element('span', '', new Date(data.queriedAt).toLocaleString('pt-BR')));
      panel.append(queryFooter);
    }
    if (data.note) body.append(element('p', 'connection-tooltip-note', data.note));
  }
  function position() {
    if (!panel || panel.hidden || !lastPoint) return;
    const rect = panel.getBoundingClientRect(), margin = 8, offset = 16;
    const x = Math.max(margin, Math.min(lastPoint.x - rect.width / 2, innerWidth - rect.width - margin));
    const above = lastPoint.y - rect.height - offset;
    const below = lastPoint.y + offset;
    const fitsAbove = above >= margin;
    const fitsBelow = below + rect.height <= innerHeight - margin;
    const y = fitsAbove ? above : fitsBelow ? below : Math.max(margin, Math.min(above, innerHeight - rect.height - margin));
    panel.style.left = x + 'px'; panel.style.top = y + 'px';
    panel.style['--pointer-x'] = Math.max(18, Math.min(lastPoint.x - x, rect.width - 18)) + 'px';
    const pointsAtLink = lastPoint.x >= x + 18 && lastPoint.x <= x + rect.width - 18;
    panel.setAttribute('data-pointer', pointsAtLink && (fitsAbove || fitsBelow) ? (fitsAbove ? 'bottom' : 'top') : 'none');
  }
  function cancelOpen() {
    clearTimeout(openTimer);
    openTimer = null; pending = null;
  }
  function watchDismissal() {
    if (watchingDismissal) return;
    watchingDismissal = true;
    window.addEventListener('resize', hide); window.addEventListener('scroll', hide, true);
    document.addEventListener('keydown', event => { if (event.key === 'Escape') hide(); });
  }
  function scheduleShow(id, source, point, data) {
    watchDismissal();
    if (activeId === id && panel && !panel.hidden) { show(id, source, point, data); return; }
    if (pending?.id === id) { Object.assign(pending, {source, point, data}); return; }
    hide();
    pending = {id, source, point, data};
    openTimer = setTimeout(() => {
      const next = pending;
      if (next) show(next.id, next.source, next.point, next.data);
    }, 1000);
  }
  function hide() {
    cancelOpen();
    if (panel) panel.hidden = true;
    activeSource?.removeAttribute('aria-describedby'); activeSource = null; activeId = null;
  }
  function show(id, source, point, data) {
    cancelOpen();
    watchDismissal();
    if (!panel) {
      panel = element('div', 'map-connection-tooltip'); panel.id = 'map-connection-tooltip';
      panel.setAttribute('role', 'tooltip'); document.body.append(panel);
    }
    if (activeId !== id || panel.hidden) render(data);
    activeSource?.removeAttribute('aria-describedby'); activeSource = source;
    source.setAttribute('aria-describedby', panel.id);
    activeId = id; lastPoint = point; panel.hidden = false; position();
  }
  function refresh(id, data) { if (panel && !panel.hidden && activeId === id) { render(data); position(); } }
  return {details, show, scheduleShow, hide, refresh};
})();
if (typeof module !== 'undefined') module.exports = MapConnectionTooltip;
