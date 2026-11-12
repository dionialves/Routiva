(() => {
  'use strict';
  const $ = id => document.getElementById('ap-' + id);
  let rows = [], hosts = [], selected = new Set(), page = 1, busy = false;
  const normalize = value => String(value ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  async function request(url, options = {}) {
    const response = await fetch(url, { cache: 'no-store', ...options });
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.message || 'Não foi possível concluir a operação. Tente novamente.');
    }
    return response.json();
  }
  function render() {
    const query = normalize($('search').value);
    const filtered = rows.filter(row => normalize([row.name, row.ip, row.manufacturer, row.model].join(' ')).includes(query));
    const pages = Math.max(1, Math.ceil(filtered.length / 20));
    page = Math.max(1, Math.min(page, pages));
    const fragment = document.createDocumentFragment();
    for (const row of filtered.slice((page - 1) * 20, page * 20)) {
      const tr = document.createElement('tr'), th = document.createElement('th'), link = document.createElement('a');
      th.scope = 'row'; link.textContent = row.name; link.className = 'availability-host-name';
      link.href = '/ap/' + encodeURIComponent(row.hostId);
      th.append(link); tr.append(th);
      for (const value of [row.ip, [row.manufacturer, row.model].filter(Boolean).join(' / '), row.available ? 'No inventário' : 'Indisponível no inventário', row.connectedClients, row.lastCollectedAt ? new Date(row.lastCollectedAt).toLocaleString('pt-BR') : null]) {
        const td = document.createElement('td'); td.textContent = value ?? '—'; tr.append(td);
      }
      fragment.append(tr);
    }
    $('rows').replaceChildren(fragment);
    $('state').textContent = rows.length === 0 ? 'Nenhum AP cadastrado. Clique em Importar para definir seus Access Points.' : filtered.length === 0 ? 'Nenhum AP encontrado para esta busca.' : `${filtered.length} Access Point(s). Clientes e última leitura refletem a última consulta válida na página do AP.`;
    $('page-count').textContent = `${page} de ${pages}`;
    $('previous').disabled = page <= 1; $('next').disabled = page >= pages;
  }
  async function load() {
    $('retry').hidden = true;
    try { rows = await request('/api/ap'); render(); }
    catch (error) { $('state').textContent = error.message; $('retry').hidden = false; }
  }
  function lists() {
    for (const side of ['available', 'selected']) {
      const query = normalize($(side + '-search').value);
      const group = hosts.filter(host => selected.has(host.id) === (side === 'selected'));
      $(side + '-count').textContent = `(${group.length})`;
      const options = group.filter(host => normalize(host.name + ' ' + host.ip).includes(query)).map(host => {
        const option = document.createElement('option'); option.value = host.id;
        option.textContent = `${host.name} · ${host.ip || 'Sem IP'}`; return option;
      });
      $(side).replaceChildren(...options);
    }
    controls();
  }
  function controls() {
    $('select').disabled = busy || $('available').selectedOptions.length === 0;
    $('remove').disabled = busy || $('selected').selectedOptions.length === 0;
    $('save').disabled = busy || selected.size === 0;
    for (const id of ['available', 'selected', 'available-search', 'selected-search', 'close', 'cancel']) $(id).disabled = busy;
  }
  $('import').addEventListener('click', async () => {
    hosts = []; selected = new Set(); busy = true;
    $('available-search').value = ''; $('selected-search').value = ''; lists();
    $('import-state').textContent = 'Carregando hosts do inventário…'; $('dialog').showModal();
    try { hosts = await request('/api/ap/candidates'); $('import-state').textContent = hosts.length ? 'Marque os hosts e use → para selecionar ou ← para retirar.' : 'Não há hosts disponíveis para importar.'; }
    catch (error) { $('import-state').textContent = error.message; }
    finally { busy = false; lists(); }
  });
  for (const [button, side, add] of [['select', 'available', true], ['remove', 'selected', false]]) {
    $(button).addEventListener('click', () => {
      for (const option of $(side).selectedOptions) { if (add) selected.add(option.value); else selected.delete(option.value); }
      lists();
    });
  }
  for (const side of ['available', 'selected']) {
    $(side + '-search').addEventListener('input', lists);
    $(side).addEventListener('change', controls);
  }
  for (const id of ['close', 'cancel']) $(id).addEventListener('click', () => $('dialog').close());
  $('dialog').addEventListener('cancel', event => { if (busy) event.preventDefault(); });
  $('save').addEventListener('click', async () => {
    busy = true; controls(); $('import-state').textContent = 'Definindo hosts como AP…';
    try {
      rows = await request('/api/ap', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ hostIds: [...selected] }) });
      page = 1; render(); $('dialog').close(); $('state').textContent = 'Access Points cadastrados com sucesso.';
    } catch (error) { $('import-state').textContent = error.message; }
    finally { busy = false; controls(); }
  });
  $('search').addEventListener('input', () => { page = 1; render(); });
  $('previous').addEventListener('click', () => { page--; render(); });
  $('next').addEventListener('click', () => { page++; render(); });
  $('retry').addEventListener('click', load);
  load();
})();
