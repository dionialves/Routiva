/* Presentation only: assessments and traffic are supplied by WirelessQuality. */
(() => {
  'use strict';
  const levels = ['Normal', 'Atenção', 'Ruim', 'Crítico'];
  const paths = {
    activity:'M2 12h4l3-9 6 18 3-9h4',
    signal:'M4 20v-4m5 4v-8m5 8V8m5 12V4',
    gauge:'M4 19a9 9 0 1 1 16 0M12 13l4-5M8 19h8',
    up:'M12 17V7m-4 4 4-4 4 4M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
    down:'M12 7v10m-4-4 4 4 4-4M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
    alert:'m12 3 10 18H2L12 3Zm0 6v5m0 3h.01',
  };
  function icon(name) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true');
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', paths[name]); svg.append(path); return svg;
  }
  function element(tag, className, text) {
    const node = document.createElement(tag); node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }
  function create() {
    const card = element('aside', 'wireless-diagnosis');
    card.id = 'wireless-diagnosis'; card.hidden = true; card.setAttribute('role', 'tooltip');
    const header = element('div', 'wireless-diagnosis-header');
    const mark = element('span', 'wireless-diagnosis-mark'); mark.append(icon('activity'));
    const title = element('strong', '', 'Diagnóstico da conexão');
    const badge = element('span', 'wireless-diagnosis-badge'); header.append(mark, title, badge);
    const summary = element('p', 'wireless-diagnosis-summary');
    const metrics = element('dl', 'wireless-diagnosis-metrics');
    const reason = element('div', 'wireless-diagnosis-reason'); reason.append(icon('alert'));
    const reasonText = element('span', ''); reason.append(reasonText);
    card.append(header, summary, metrics, reason); document.body.append(card);
    let selected = null, anchor = null, opening, closing, pointer = null, lastContent = '';
    const key = row => JSON.stringify([row['mac-address'], row.interface]);
    function hide() {
      clearTimeout(opening); clearTimeout(closing);
      anchor?.removeAttribute('aria-describedby'); selected = null; anchor = null; pointer = null; card.hidden = true;
    }
    function position() {
      if (card.hidden || !anchor?.isConnected) return;
      const row = anchor.getBoundingClientRect(), bounds = card.getBoundingClientRect();
      const width = document.documentElement.clientWidth, height = window.innerHeight;
      const x = pointer?.x ?? row.right, y = pointer?.y ?? row.top;
      let left = x + 12;
      if (left + bounds.width > width - 8) left = x - bounds.width - 12;
      if (left < 8) left = Math.min(x + 12, width - bounds.width - 8);
      card.style.left = Math.max(8, left) + 'px';
      const top = Math.max(8, Math.min(pointer ? y + 12 : y, height - bounds.height - 8));
      card.style.top = top + 'px';
      card.style.maxHeight = Math.max(0, height - top - 8) + 'px';
    }
    function render(client, available) {
      const content = JSON.stringify([client, available]);
      if (content === lastContent) return;
      lastContent = content;
      const severity = String(client['quality-severity'] ?? -1);
      card.dataset.level = severity; badge.textContent = client.quality || 'Indisponível';
      summary.textContent = !available ? 'Dados desatualizados · última leitura válida.'
        : client['connection-status'] === 'disconnected' ? 'Cliente desconectado · últimos valores.'
        : client['quality-summary'] || 'Dados insuficientes para avaliação.';
      const fragment = document.createDocumentFragment();
      const traffic = ['tx-traffic-bps', 'rx-traffic-bps'].map(field => {
        const raw = client[field];
        return raw == null || raw === '' ? NaN : Number(raw);
      });
      const totalTraffic = traffic.every(value => Number.isFinite(value) && value >= 0)
        ? traffic[0] + traffic[1] : NaN;
      const rows = [
        ['Sinal médio', 'avg-signal', ' dBm', 'signal', 'signal'],
        ['SNR médio', 'avg-signal-to-noise', ' dB', 'activity', 'signal-to-noise'],
        ['TX CCQ médio', 'avg-tx-ccq', '%', 'gauge'],
        ['Tráfego total atual', 'total-traffic-bps', ' kbps', 'activity'],
      ];
      for (const [label, field, unit, image, assessment] of rows) {
        const raw = client[field];
        const value = field === 'total-traffic-bps' ? totalTraffic
          : raw == null || raw === '' ? NaN : Number(raw);
        const line = element('div', ''); const dt = element('dt', '', label); dt.prepend(icon(image));
        const isTraffic = field === 'total-traffic-bps';
        const megabits = isTraffic && value > 2000000;
        const displayValue = isTraffic ? value / (megabits ? 1000000 : 1000) : value;
        const displayUnit = megabits ? ' Mbps' : unit;
        const dd = element('dd', '', Number.isFinite(value)
          ? displayValue.toLocaleString('pt-BR', { minimumFractionDigits:isTraffic ? 0 : 2, maximumFractionDigits:2 }) + displayUnit : '—');
        const level = client[assessment + '-severity'];
        if (assessment && levels[level]) {
          dd.dataset.level = level; const state = element('small', '', levels[level]); dd.append(state);
        }
        line.append(dt, dd); fragment.append(line);
      }
      metrics.replaceChildren(fragment);
      const strong = element('strong', '', 'Motivo principal: ');
      reasonText.replaceChildren(strong, document.createTextNode(client['quality-primary-reason'] || 'Dados insuficientes para determinar a causa.'));
    }
    function scheduleClose() { clearTimeout(opening); clearTimeout(closing); closing = setTimeout(hide, 180); }
    function bind(row, client, available) {
      function show(delay, event) {
        if (event && Number.isFinite(event.clientX) && Number.isFinite(event.clientY)) {
          pointer = { x:event.clientX, y:event.clientY };
        } else if (selected !== key(client)) pointer = null;
        clearTimeout(closing); clearTimeout(opening);
        if (selected === key(client) && !card.hidden) { anchor = row; return; }
        opening = setTimeout(() => {
          anchor?.removeAttribute('aria-describedby'); anchor = row; selected = key(client);
          row.setAttribute('aria-describedby', card.id); render(client, available); card.hidden = false; position();
        }, delay);
      }
      row.addEventListener('pointerenter', event => { if (event.pointerType !== 'touch') show(200, event); });
      row.addEventListener('pointermove', event => {
        if (event.pointerType === 'touch') return;
        pointer = { x:event.clientX, y:event.clientY };
        if (selected === key(client) && !card.hidden) position();
      });
      row.addEventListener('pointerleave', scheduleClose);
      row.addEventListener('focusin', () => show(200));
      row.addEventListener('focusout', scheduleClose);
      row.addEventListener('pointerup', event => {
        if (event.pointerType !== 'touch' || event.target.closest('button')) return;
        show(0, event);
      });
    }
    card.addEventListener('pointerenter', () => clearTimeout(closing));
    card.addEventListener('pointerleave', scheduleClose);
    document.addEventListener('keydown', event => { if (event.key === 'Escape') hide(); });
    document.addEventListener('pointerdown', event => {
      if (!card.hidden && !card.contains(event.target) && !anchor?.contains(event.target)) hide();
    });
    window.addEventListener('resize', position);
    document.addEventListener('scroll', position, true);
    return { bind, hide, update(clients, rows, available) {
      if (!selected) return;
      const client = clients.find(client => key(client) === selected);
      const row = Array.from(rows).find(row => JSON.stringify([row.dataset.clientMac, row.dataset.clientInterface]) === selected);
      if (!client || !row) { hide(); return; }
      anchor = row; row.setAttribute('aria-describedby', card.id); render(client, available);
    } };
  }
  window.HostWirelessTooltip = { create };
})();
