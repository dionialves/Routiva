'use strict';
(() => {
  const $ = id => document.getElementById(id);
  let connections = [], hosts = [], readings = new Map(), busy = false;
  const rate = value => value == null ? 'Sem dados' : (Number(value) / 1000000).toLocaleString('pt-BR', {maximumFractionDigits:2}) + ' Mbps';
  const status = e => !e ? 'Aguardando coleta' : Date.now() - e.measuredAt > 30000 ? 'Desatualizado' : e.error || (e.up ? 'UP' : 'DOWN');
  async function request(url, body) {
    const response = await fetch(url, body === undefined ? {} : {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)});
    if (!response.ok) throw new Error('Não foi possível consultar as conexões. Tente novamente.');
    return response.json();
  }
  function render() {
    const query = $('connection-search').value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
    const selected = decodeURIComponent(location.hash.slice(1));
    $('connection-rows').replaceChildren();
    const detail = connections.find(c => c.id === selected);
    $('connection-detail').hidden = !detail;
    if (detail) {
      $('connection-detail-title').textContent = detail.name;
      const reading = readings.get(detail.id);
      for (const [side, hostId, iface, endpoint] of [['a', detail.hostA, detail.interfaceA, reading?.a], ['b', detail.hostB, detail.interfaceB, reading?.b]]) {
        $('connection-detail-' + side).textContent = `${side.toUpperCase()} · ${hosts.find(h => h.id === hostId)?.name || hostId} · ${iface} · ${status(endpoint)} · RX ${rate(endpoint?.rxBps)} · TX ${rate(endpoint?.txBps)}`;
      }
    }
    const visible = connections.filter(c => c.name.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().includes(query));
    for (const c of visible) {
      const reading = readings.get(c.id), a = reading?.a, b = reading?.b;
      const name = id => hosts.find(h => h.id === id)?.name || id;
      const row = document.createElement('tr'); row.id = c.id;
      if (c.id === selected) row.setAttribute('aria-selected', 'true');
      [c.name, name(c.hostA) + ' / ' + c.interfaceA, name(c.hostB) + ' / ' + c.interfaceB,
        status(a) + ' / ' + status(b), rate(a?.rxBps) + ' / ' + rate(a?.txBps), rate(b?.rxBps) + ' / ' + rate(b?.txBps),
        a && b ? new Date(Math.min(a.measuredAt, b.measuredAt)).toLocaleString('pt-BR') : 'Sem dados'].forEach((value, index) => {
          const td = document.createElement('td');
          if (index === 0) { const link = document.createElement('a'); link.href = '#' + encodeURIComponent(c.id); link.textContent = value; td.append(link); }
          else td.textContent = value;
          row.append(td);
        });
      $('connection-rows').append(row);
    }
    $('connection-state').textContent = visible.length ? `${visible.length} conexões · atualização a cada 10 segundos.` : 'Nenhuma conexão encontrada.';
  }
  async function refresh() {
    if (busy || document.hidden) return;
    busy = true; $('connection-refresh').disabled = true;
    try {
      [connections, hosts] = await Promise.all([request('/api/connections'), request('/api/maps/hosts')]);
      render();
      const rows = [];
      for (let offset = 0; offset < connections.length; offset += 100) {
        rows.push(...await request('/api/connections/readings', connections.slice(offset, offset + 100).map(c => c.id)));
      }
      readings = new Map(rows.map(r => [r.id, r])); render();
      if (location.hash) document.getElementById(decodeURIComponent(location.hash.slice(1)))?.scrollIntoView({block:'nearest'});
    } catch (error) { $('connection-state').textContent = error.message; }
    finally { busy = false; $('connection-refresh').disabled = false; }
  }
  window.addEventListener('hashchange', render);
  $('connection-search').oninput = render;
  $('connection-refresh').onclick = refresh;
  document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
  setInterval(refresh, 10000); refresh();
})();
