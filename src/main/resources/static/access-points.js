(() => {
  'use strict';
  const $ = id => document.getElementById('ap-' + id);
  let rows = [], hosts = [], selected = new Set(), page = 1, busy = false;
  const normalize = value => String(value ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  const marked = { available: new Set(), selected: new Set() };
  let loading = false;
  let sortKey = 'name', direction = 1;
  const hostSelect = document.getElementById('incident-host');
  const groupSelect = document.getElementById('incident-group');
  let refreshGroupPicker;
  let pickerSignature = '';
  let refreshHostPicker;
  const pageSize = 50;
  const paths = {
    wifi: '<path d="M2 8.82a15 15 0 0 1 20 0M5 12a10 10 0 0 1 14 0M8.5 15.5a5 5 0 0 1 7 0"/><circle cx="12" cy="19" r="1"/>',
    upload: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12"/>',
    search: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
    'chevron-down': '<path d="m6 9 6 6 6-6"/>',
    'chevron-left': '<path d="m15 18-6-6 6-6"/>',
    'chevron-right': '<path d="m9 18 6-6-6-6"/>',
    x: '<path d="m18 6-12 12M6 6l12 12"/>',
    'arrow-right': '<path d="M5 12h14m-7-7 7 7-7 7"/>',
    'arrow-left': '<path d="M19 12H5m7 7-7-7 7-7"/>',
    'trash-2': '<path d="M3 6h18M19 6l-1 14H6L5 6m4 0V3h6v3M10 10v7m4-7v7"/>',
    info: '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4m0-4h.01"/>',
    'loader-circle': '<path d="M12 2a10 10 0 1 0 10 10"/>',
    ellipsis: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>'
  };
  function icon(name) {
    const span = document.createElement('span');
    span.className = 'ap-icon';
    span.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name]}</svg>`;
    return span;
  }
  document.querySelectorAll('[data-ap-icon]').forEach(el => el.append(icon(el.dataset.apIcon)));
  function renderHostPicker() {
    const signature = JSON.stringify(rows.map(row => [row.hostId, row.name, row.ip, row.groups]));
    if (signature === pickerSignature && refreshHostPicker) return;
    pickerSignature = signature;
    refreshHostPicker?.dispose?.();
    refreshGroupPicker?.dispose?.();
    const selectedGroups = new Set([...groupSelect.selectedOptions].map(option => option.value));
    const groups = [...new Map(rows.flatMap(row => row.groups || []).map(group => [group.id, group])).values()];
    groups.sort((a, b) => a.name.localeCompare(b.name, 'pt-BR'));
    groupSelect.replaceChildren(...groups.map(group => {
      const option = document.createElement('option');
      option.value = group.id; option.textContent = group.name;
      option.selected = selectedGroups.has(group.id); return option;
    }));
    document.getElementById('incident-group-options').replaceChildren();
    refreshGroupPicker = window.setupMultiPicker('group', groupSelect, 'Todos os grupos', () => {
      page = 1; render();
    });
    const selectedIds = new Set([...hostSelect.selectedOptions].map(option => option.value));
    hostSelect.replaceChildren(...rows.map(row => {
      const option = document.createElement('option');
      option.value = row.hostId; option.textContent = `${row.name} · ${row.ip || 'Sem IP'}`;
      option.selected = selectedIds.has(row.hostId); return option;
    }));
    document.getElementById('incident-host-options').replaceChildren();
    refreshHostPicker = window.setupMultiPicker('host', hostSelect, 'Todos os hosts', () => {
      page = 1; render();
    });
  }
  async function request(url, options = {}) {
    const response = await fetch(url, { cache: 'no-store', ...options });
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.message || 'Não foi possível concluir a operação. Tente novamente.');
    }
    return response.json();
  }
  function render() {
    const selectedHosts = new Set([...hostSelect.selectedOptions].map(option => option.value));
    const selectedGroups = new Set([...groupSelect.selectedOptions].map(option => option.value));
    const filtered = rows.filter(row => (!selectedHosts.size || selectedHosts.has(row.hostId))
      && (!selectedGroups.size || (row.groups || []).some(group => selectedGroups.has(group.id))));
    filtered.sort((a, b) => {
      const left = a[sortKey], right = b[sortKey];
      if (left == null || right == null) return left == null ? (right == null ? 0 : 1) : -1;
      return direction * (typeof left === 'number' || typeof left === 'boolean'
        ? Number(left) - Number(right)
        : String(left).localeCompare(String(right), 'pt-BR', { numeric: true }));
    });
    const pages = Math.max(1, Math.ceil(filtered.length / pageSize));
    page = Math.max(1, Math.min(page, pages));
    const fragment = document.createDocumentFragment();
    for (const row of filtered.slice((page - 1) * pageSize, page * pageSize)) {
      const tr = document.createElement('tr'), th = document.createElement('th'), link = document.createElement('a');
      th.scope = 'row'; link.textContent = row.name; link.className = 'availability-host-name';
      link.href = '/ap/' + encodeURIComponent(row.hostId);
      th.append(link); tr.append(th);
      for (const value of [row.ip, [row.manufacturer, row.model].filter(Boolean).join(' / '), row.connectedClients, row.lastCollectedAt ? new Date(row.lastCollectedAt).toLocaleString('pt-BR') : null]) {
        const td = document.createElement('td'); td.textContent = value ?? '—';
        tr.append(td);
      }
      fragment.append(tr);
    }
    $('rows').replaceChildren(fragment);
    globalThis.TableState?.done('ap-rows');
    TablePagination.render('ap-pagination', page, pageSize, filtered.length, target => { page = target; render(); });

  }
  async function load() {
    $('retry').hidden = true;
    globalThis.TableState?.loading('ap-rows');
    try { rows = await request('/api/ap'); renderHostPicker(); render(); }
    catch (error) { globalThis.TableState?.error('ap-rows', error.message); $('retry').hidden = false; }
  }
  function feedback(message, pending = false) {
    $('import-state').textContent = message;
    $('import-status-icon').replaceChildren(icon(pending ? 'loader-circle' : 'info'));
    $('import-status-icon').className = pending ? 'ap-spinning' : '';
  }
  function lists() {
    for (const side of ['available', 'selected']) {
      const query = normalize($(side + '-search').value);
      const group = hosts.filter(host => selected.has(host.id) === (side === 'selected'));
      $(side + '-count').textContent = `(${group.length})`;
      const visible = group.filter(host => normalize(host.name + ' ' + (host.ip || 'Sem IP')).includes(query));
      const children = visible.map(host => {
        const row = document.createElement('div'); row.className = 'ap-host-row';
        const label = document.createElement('label');
        const fullText = `${host.name} · ${host.ip || 'Sem IP'}`;
        label.title = fullText; label.setAttribute('aria-label', fullText);
        const checkbox = document.createElement('input'); checkbox.type = 'checkbox';
        checkbox.checked = marked[side].has(host.id); checkbox.disabled = busy;
        checkbox.addEventListener('change', () => {
          if (checkbox.checked) marked[side].add(host.id); else marked[side].delete(host.id);
          controls();
        });
        const name = document.createElement('span'); name.className = 'ap-host-name'; name.textContent = host.name;
        const ip = document.createElement('span'); ip.className = 'ap-host-ip';
        ip.textContent = host.ip ? `· ${host.ip}` : 'Sem IP';
        label.append(checkbox, name, ip); row.append(label);
        return row;
      });
      if (!children.length) {
        const empty = document.createElement(group.length || side === 'available' ? 'p' : 'div');
        empty.className = 'ap-list-empty';
        if (side === 'selected' && group.length === 0) {
          empty.className = 'ap-list-empty ap-list-empty-selection';
          empty.append(icon('wifi'));
          const title = document.createElement('strong'); title.textContent = 'Nenhum host selecionado.';
          const description = document.createElement('span');
          description.textContent = 'Selecione hosts na lista ao lado e adicione à seleção.';
          empty.append(title, description);
        } else {
          empty.textContent = loading ? 'Carregando inventário…' : group.length ? 'Nenhum host encontrado.' : 'Nenhum host disponível para importação.';
        }
        children.push(empty);
      }
      $(side).replaceChildren(...children);
    }
    controls();
  }
  function controls() {
    $('select').disabled = busy || marked.available.size === 0;
    $('remove').disabled = busy || marked.selected.size === 0;
    $('save').disabled = busy || selected.size === 0;
    $('save-label').textContent = busy && !loading ? 'Definindo como AP…' : 'Definir como AP';
    $('save').className = `system-button system-button-primary${busy ? ' system-button-loading' : ''}`;
    $('save').setAttribute('aria-busy', String(busy));
    $('dialog').setAttribute('aria-busy', String(busy));
    for (const id of ['available-search', 'selected-search', 'close', 'cancel']) $(id).disabled = busy;
  }
  $('import').addEventListener('click', async () => {
    hosts = []; selected = new Set(); marked.available.clear(); marked.selected.clear();
    busy = true; loading = true;
    $('available-search').value = ''; $('selected-search').value = ''; lists();
    feedback('Carregando hosts do inventário…', true); $('dialog').showModal();
    try {
      const candidates = await request('/api/ap/candidates');
      if (!Array.isArray(candidates)) throw new Error('Resposta inválida ao consultar o inventário. Tente novamente.');
      hosts = [...new Map(candidates.map(host => [host.id, host])).values()];
      feedback('Marque os hosts e use → para selecionar ou ← para retirar.');
    } catch (error) { feedback(error.message); }
    finally { busy = false; loading = false; lists(); }
  });
  for (const [button, side, add] of [['select', 'available', true], ['remove', 'selected', false]]) {
    $(button).addEventListener('click', () => {
      if (busy) return;
      for (const id of marked[side]) { if (add) selected.add(id); else selected.delete(id); }
      marked[side].clear(); lists();
    });
  }
  for (const side of ['available', 'selected']) $(side + '-search').addEventListener('input', lists);
  for (const id of ['close', 'cancel']) $(id).addEventListener('click', () => { if (!busy) $('dialog').close(); });
  $('dialog').addEventListener('cancel', event => { if (busy) event.preventDefault(); });
  $('save').addEventListener('click', async () => {
    if (busy || selected.size === 0) return;
    busy = true; lists(); feedback('Definindo hosts como AP…', true);
    try {
      const imported = await request('/api/ap', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ hostIds: [...selected] }) });
      if (!Array.isArray(imported)) throw new Error('Não foi possível confirmar a importação. Tente novamente.');
      rows = imported;
      page = 1; renderHostPicker(); render(); feedback('Importação concluída.'); $('dialog').close();
    } catch (error) { feedback(error.message); }
    finally { busy = false; lists(); }
  });
  for (const key of ['name', 'ip', 'manufacturer', 'connectedClients', 'lastCollectedAt']) {
    $('sort-' + key).addEventListener('click', () => {
      direction = sortKey === key ? -direction : 1; sortKey = key;
      updateSort(); render();
    });
  }
  function updateSort() {
    for (const key of ['name', 'ip', 'manufacturer', 'connectedClients', 'lastCollectedAt']) {
      const button = $('sort-' + key);
      button.parentElement.setAttribute('aria-sort', sortKey === key ? (direction === 1 ? 'ascending' : 'descending') : 'none');
    }
  }
  updateSort();
  $('retry').addEventListener('click', load);
  load();
})();
