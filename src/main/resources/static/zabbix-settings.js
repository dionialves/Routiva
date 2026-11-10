/* One host ID has one selection, including when it belongs to several groups. */
class ZabbixSelection {
  constructor() {
    this.selected = new Set();
    this.saved = new Set();
    this.imported = new Map();
    this.groups = [];
    this.hosts = new Map();
    this.members = new Map();
    this.loaded = false;
  }
  load(data) {
    if (!this.loaded) {
      this.selected = new Set(data.selectedHosts);
      this.saved = new Set(data.selectedHosts);
    }
    this.imported = new Map(data.imported.map(h => [h.id, h]));
    this.hosts = new Map(data.hosts.map(h => [h.id, h]));
    this.groups = [...data.groups];
    this.members = new Map(this.groups.map(g => [g.id, []]));
    for (const host of this.hosts.values()) {
      for (const group of host.groups) this.members.get(group.id)?.push(host);
    }
    const missing = [...new Set([...this.selected, ...this.saved])]
      .filter(id => !this.hosts.has(id))
      .map(id => ({ ...(this.imported.get(id) || { id, name: id, ip: '' }), missing: true }));
    if (missing.length) {
      this.groups.push({ id: '__missing', name: 'Ausentes da origem' });
      this.members.set('__missing', missing);
    }
    this.loaded = true;
  }
  toggle(id, checked) { checked ? this.selected.add(id) : this.selected.delete(id); }
  toggleGroup(id, checked) {
    (this.members.get(id) || []).forEach(h => this.toggle(h.id, checked));
  }
  changes() {
    return [...new Set([...this.saved, ...this.selected])]
      .filter(id => this.saved.has(id) !== this.selected.has(id)).length;
  }
  cancel() { this.selected = new Set(this.saved); }
  commit(data) {
    this.saved = new Set(data.selectedHosts);
    this.selected = new Set(data.selectedHosts);
    this.imported = new Map(data.imported.map(h => [h.id, h]));
  }
}
if (typeof module !== 'undefined') module.exports = { ZabbixSelection };
if (typeof document !== 'undefined') document.addEventListener('DOMContentLoaded', () => {
  const $ = id => document.getElementById(id);
  const state = new ZabbixSelection();
  const results = new ZabbixImportResults(document, window.AppIcon);
  const form = $('connection-form');
  let current = null, page = 0, busy = false;
  let configured = document.querySelector('.settings-page').dataset.configured === 'true';
  const pageSize = 50;
  const normalize = value => String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  const node = (tag, text, className = '') => {
    const el = document.createElement(tag);
    el.textContent = text; el.className = className;
    return el;
  };
  function checkbox(label, checked, partial, change) {
    const input = document.createElement('input');
    input.type = 'checkbox'; input.checked = checked; input.indeterminate = partial;
    input.setAttribute('aria-label', label); input.disabled = busy;
    input.addEventListener('change', () => { change(input.checked); render(); });
    return input;
  }
  function connection(status, text) {
    $('connection-status').dataset.state = status;
    $('connection-status-text').textContent = text;
  }
  function renderGroups() {
    const list = $('group-list'); list.replaceChildren();
    const query = normalize($('group-search').value);
    state.groups.filter(g => normalize(g.name).includes(query)).forEach(group => {
      const hosts = state.members.get(group.id) || [];
      const count = hosts.filter(h => state.selected.has(h.id)).length;
      const row = node('div', '', `zabbix-group${current === group.id ? ' active' : ''}`);
      const check = checkbox(`Selecionar grupo ${group.name}`, hosts.length > 0 && count === hosts.length,
        count > 0 && count < hosts.length, checked => state.toggleGroup(group.id, checked));
      const button = node('button', group.name); button.type = 'button';
      button.setAttribute('aria-pressed', String(current === group.id));
      button.addEventListener('click', () => { current = group.id; page = 0; render(); });
      row.append(check, button, node('small', `${count}/${hosts.length}`)); list.append(row);
    });
    if (!list.children.length) list.append(node('p', state.groups.length ? 'Nenhum grupo corresponde à pesquisa.' : 'Sem grupos encontrados.'));
  }
  function renderHosts() {
    const group = state.groups.find(g => g.id === current);
    const members = state.members.get(current) || [];
    $('group-title').textContent = group ? `${group.name} · ${members.length} hosts` : 'Selecione um grupo';
    const count = members.filter(h => state.selected.has(h.id)).length;
    $('group-all').checked = members.length > 0 && count === members.length;
    $('group-all').indeterminate = count > 0 && count < members.length;
    $('group-all').disabled = busy || !members.length;
    const query = normalize($('host-search').value);
    const filter = $('selection-filter').value;
    const matches = members.filter(h => normalize(`${h.name} ${h.hostname || ''} ${h.ip}`).includes(query)
      && (filter === 'all' || state.selected.has(h.id) === (filter === 'selected')));
    const pages = Math.max(1, Math.ceil(matches.length / pageSize));
    page = Math.min(page, pages - 1);
    const list = $('host-list'); list.replaceChildren();
    matches.slice(page * pageSize, (page + 1) * pageSize).forEach(host => {
      const row = node('tr');
      const cell = node('td');
      const input = checkbox(`Selecionar host ${host.name}`, state.selected.has(host.id), false,
        checked => state.toggle(host.id, checked));
      input.id = `host-select-${host.id}`; cell.append(input);
      const name = node('td'), label = node('label', host.name);
      label.htmlFor = input.id; name.append(label);
      row.append(cell, name, node('td', host.ip || 'Sem IP'),
        node('td', `${state.imported.has(host.id) ? 'Importado' : 'Não importado'}${host.missing ? ' · Ausente da origem' : ''}`));
      list.append(row);
    });
    if (!matches.length) {
      const row = node('tr'), cell = node('td', members.length ? 'Nenhum host corresponde ao filtro.' : 'Nenhum host neste grupo.', 'table-empty');
      cell.colSpan = 4; row.append(cell); list.append(row);
    }
    $('page-label').textContent = `${matches.length} resultados · Página ${page + 1} de ${pages}`;
    $('previous-page').disabled = page === 0; $('next-page').disabled = page + 1 >= pages;
  }
  function render() {
    const focus = document.activeElement;
    const label = focus?.getAttribute('aria-label');
    renderGroups(); renderHosts();
    $('groups-total').textContent = state.groups.filter(g => g.id !== '__missing').length;
    $('hosts-total').textContent = state.hosts.size;
    $('hosts-selected').textContent = state.selected.size;
    const fresh = [...state.selected].filter(id => state.hosts.has(id) && !state.imported.has(id)).length;
    $('selection-count').textContent = `${state.selected.size} hosts selecionados`;
    $('selection-changes').textContent = `${fresh} novos · ${state.changes()} seleções alteradas${state.changes() ? ' · Alterações pendentes' : ''}`;
    $('save-selection-text').textContent = busy ? 'Aguarde…' : 'Salvar e importar';
    $('refresh-inventory').disabled = busy || !configured;
    $('test-connection').disabled = busy || !configured;
    $('save-selection').disabled = busy || !state.loaded;
    $('cancel-selection').disabled = busy || !state.changes();
    if (label && !focus.isConnected) {
      [...document.querySelectorAll('input[aria-label]')].find(el => el.getAttribute('aria-label') === label)?.focus();
    }
  }
  function result(data) {
    results.load(data, [...state.imported.values()]);
  }
  async function request(action, data) {
    const response = await fetch(`/configuracao/zabbix/api/${action}`, { method: 'POST', body: data });
    const body = await response.json().catch(() => ({ message: 'Resposta inválida do servidor.' }));
    if (!response.ok) throw new Error(body.message || 'Não foi possível concluir a operação.');
    return body;
  }
  async function run(operation) {
    if (busy) return;
    busy = true;
    $('refresh-inventory').disabled = true;
    form.querySelectorAll('button').forEach(el => { el.disabled = true; });
    $('inventory-panel').setAttribute('aria-busy', 'true');
    render();
    try { await operation(); }
    catch (error) { $('inventory-feedback').textContent = error.message; window.notify(error.message, 'error'); }
    finally {
      busy = false; $('refresh-inventory').disabled = !configured;
      form.querySelectorAll('button').forEach(el => { el.disabled = false; });
      $('inventory-panel').setAttribute('aria-busy', 'false'); render();
    }
  }
  async function refresh() {
    connection('loading', 'Verificando conexão…');
    $('inventory-feedback').textContent = 'Consultando grupos e hosts…';
    try {
      const response = await fetch('/configuracao/zabbix/inventario');
      if (!response.ok) throw new Error('Falha ao consultar o Zabbix. Verifique a conexão e tente novamente.');
      const data = await response.json(); state.load(data);
      if (!state.groups.some(g => g.id === current)) current = state.groups[0]?.id;
      $('last-query').textContent = new Date(data.queriedAt).toLocaleString('pt-BR');
      connection('connected', 'Conectado'); $('inventory-feedback').textContent = '';
      result(data.result);
    } catch (error) { connection('error', 'Falha de conexão'); throw error; }
  }
  $('refresh-inventory').addEventListener('click', () => run(refresh));
  form.addEventListener('submit', event => {
    event.preventDefault();
    run(async () => {
      const previousUrl = form.dataset.savedUrl || form.elements.url.defaultValue;
      if (previousUrl !== form.elements.url.value.trim() && state.changes()
        && !window.confirm('A troca de servidor descarta a seleção pendente. Continuar?')) return;
      const data = await request('conexao', new URLSearchParams(new FormData(form)));
      $('connection-feedback').textContent = data.message;
      form.elements.password.value = '';
      form.elements.password.placeholder = 'Deixe em branco para manter a senha salva';
      if (previousUrl !== form.elements.url.value.trim()) {
        Object.assign(state, new ZabbixSelection()); current = null; result(null);
      }
      configured = true;
      form.dataset.savedUrl = form.elements.url.value.trim();
      connection('loading', 'Conexão salva; aguardando consulta');
    });
  });
  $('test-connection').addEventListener('click', () => run(async () => {
    connection('loading', 'Verificando conexão…');
    try {
      const data = await request('teste', new URLSearchParams({ csrf: form.elements.csrf.value }));
      $('connection-feedback').textContent = data.message; connection('connected', 'Conectado');
    } catch (error) { connection('error', 'Falha de conexão'); throw error; }
  }));
  $('save-selection').addEventListener('click', () => run(async () => {
    $('inventory-feedback').textContent = 'Salvando seleção e importando hosts…';
    const reply = await fetch('/configuracao/zabbix/api/importar', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Zabbix-CSRF': form.elements.csrf.value },
      body: JSON.stringify({ hosts: [...state.selected] })
    });
    const response = await reply.json();
    if (!reply.ok) throw new Error(response.message || 'Seleção não confirmada pelo servidor.');
    state.commit(response); result(response.result); $('inventory-feedback').textContent = '';
  }));
  $('cancel-selection').addEventListener('click', () => { state.cancel(); render(); });
  $('group-all').addEventListener('change', () => { state.toggleGroup(current, $('group-all').checked); render(); });
  $('group-search').addEventListener('input', renderGroups);
  ['host-search', 'selection-filter'].forEach(id => $(id).addEventListener(id === 'host-search' ? 'input' : 'change', () => { page = 0; renderHosts(); }));
  $('previous-page').addEventListener('click', () => { page--; renderHosts(); });
  $('next-page').addEventListener('click', () => { page++; renderHosts(); });
  window.addEventListener('beforeunload', event => {
    if (state.changes() || busy) { event.preventDefault(); event.returnValue = ''; }
  });
  render();
  run(async () => {
    try {
      const response = await fetch('/configuracao/zabbix/resultado');
      if (!response.ok) throw new Error();
      const data = await response.json();
      results.load(data.result, data.imported);
    } catch {
      $('inventory-feedback').textContent = 'Não foi possível recuperar o último resultado salvo.';
    }
    if (configured) await refresh();
    else $('inventory-feedback').textContent = 'Configure e salve a conexão para consultar os grupos e hosts.';
  });
});
