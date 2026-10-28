document.addEventListener('DOMContentLoaded', () => {
  const form = document.querySelector('#connection-form');
  const search = document.querySelector('#selection-search');
  const list = document.querySelector('#group-list');
  let groups = [], hosts = [], selected = new Set(), enabled = new Set();
  const expanded = new Set();
  const normalize = value => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  const members = group => hosts.filter(host => host.groups.some(g => g.id === group.id));
  function visible(group) {
    const query = normalize(search.value.trim());
    return normalize(group.name).includes(query) ? members(group) : members(group).filter(h => normalize(`${h.name} ${h.ip}`).includes(query));
  }
  function matches(group) { return normalize(group.name).includes(normalize(search.value.trim())) || visible(group).length > 0; }
  function element(tag, className, text) {
    const node = document.createElement(tag); node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function removeUnusedHosts() {
    const eligible = new Set(groups.filter(g => enabled.has(g.id)).flatMap(g => members(g).map(h => h.id)));
    selected.forEach(id => { if (!eligible.has(id)) selected.delete(id); });
  }
  function render() {
    const focusKey = document.activeElement?.dataset?.focusKey;
    list.replaceChildren();
    groups.filter(matches).forEach(group => {
      const items = members(group), filtered = visible(group);
      const section = element('div', 'inventory-group');
      const heading = element('div', 'group-heading');
      const checkbox = element('input', ''); checkbox.type = 'checkbox'; checkbox.dataset.focusKey = `group-${group.id}`;
      checkbox.setAttribute('aria-label', `Selecionar grupo ${group.name}`);
      checkbox.checked = enabled.has(group.id);
      checkbox.indeterminate = checkbox.checked && items.some(h => selected.has(h.id)) && !items.every(h => selected.has(h.id));
      checkbox.addEventListener('click', e => e.stopPropagation());
      checkbox.addEventListener('change', () => {
        checkbox.checked ? enabled.add(group.id) : enabled.delete(group.id);
        if (checkbox.checked) items.forEach(h => selected.add(h.id));
        removeUnusedHosts(); render();
      });
      const toggle = element('button', 'group-toggle', group.name); toggle.type = 'button';
      const open = expanded.has(group.id) || !!search.value.trim();
      toggle.setAttribute('aria-expanded', String(open));
      toggle.setAttribute('aria-controls', `group-${group.id}`);
      const body = element('div', 'group-hosts'); body.id = `group-${group.id}`; body.hidden = !open;
      const chevron = element('span', '', open ? '⌄' : '›'); chevron.setAttribute('aria-hidden', 'true');
      const expand = () => {
        body.hidden = !body.hidden;
        body.hidden ? expanded.delete(group.id) : expanded.add(group.id);
        toggle.setAttribute('aria-expanded', String(!body.hidden)); chevron.textContent = body.hidden ? '›' : '⌄';
      };
      heading.addEventListener('click', expand);
      heading.append(checkbox, toggle, element('span', 'group-count', String(items.length)), chevron);
      filtered.forEach(host => {
        const row = element('label', 'inventory-host-row');
        const input = element('input', ''); input.type = 'checkbox'; input.dataset.focusKey = `host-${group.id}-${host.id}`; input.checked = enabled.has(group.id) && selected.has(host.id); input.disabled = !enabled.has(group.id);
        input.addEventListener('change', () => { input.checked ? selected.add(host.id) : selected.delete(host.id); render(); });
        row.append(input, element('span', '', host.name), element('small', '', host.ip || 'Sem IP')); body.append(row);
      });
      if (!items.length) body.append(element('p', 'group-empty', 'Nenhum host neste grupo.'));
      section.append(heading, body); list.append(section);
    });
    if (focusKey) [...list.querySelectorAll('input')].find(input => input.dataset.focusKey === focusKey)?.focus();
    const count = new Set(groups.filter(matches).flatMap(g => visible(g).map(h => h.id))).size;
    document.querySelector('#inventory-empty').hidden = list.children.length > 0;
    document.querySelector('#selection-count').textContent = `${selected.size} hosts selecionados · ${count} hosts nos resultados`;
  }
  async function loadInventory() {
    const response = await fetch('/configuracao/zabbix/inventario');
    if (!response.ok) throw new Error('Não foi possível carregar os grupos e hosts. Tente sincronizar novamente.');
    const data = await response.json();
    groups = data.groups; hosts = data.hosts; selected = new Set(data.selectedHosts); enabled = new Set(data.selectedGroups);
    document.querySelector('#inventory-panel').hidden = false;
    document.querySelector('#inventory-hint').hidden = true;
    render();
  }
  search.addEventListener('input', render);
  function selectVisible(checked) {
    const matching = groups.filter(matches);
    matching.forEach(g => {
      if (!search.value.trim() || normalize(g.name).includes(normalize(search.value.trim()))) {
        checked ? enabled.add(g.id) : enabled.delete(g.id);
        if (checked) members(g).forEach(h => selected.add(h.id));
      } else if (checked) {
        enabled.add(g.id);
        visible(g).forEach(h => selected.add(h.id));
      } else if (enabled.has(g.id)) {
        visible(g).forEach(h => selected.delete(h.id));
      }
    });
    removeUnusedHosts(); render();
  }
  document.querySelector('#select-visible').addEventListener('click', () => selectVisible(true));
  document.querySelector('#clear-visible').addEventListener('click', () => selectVisible(false));
  let busy = false;
  async function action(name) {
    if (busy) { window.notify('Aguarde a conclusão da operação em andamento.'); return; }
    busy = true;
    const data = name === 'conexao' ? new FormData(form) : new FormData();
    if (name !== 'conexao') data.set('csrf', form.elements.csrf.value);
    if (name === 'selecao') { enabled.forEach(id => data.append('groups', id)); selected.forEach(id => data.append('hosts', id)); }
    form.setAttribute('aria-busy', 'true');
    try {
      const response = await fetch(`/configuracao/zabbix/api/${name}`, { method: 'POST', body: data });
      const result = await response.json().catch(() => ({ message: 'Não foi possível concluir a operação. Atualize a página e tente novamente.' }));
      if (!response.ok) throw new Error(result.message);
      window.notify(result.message);
      if (name === 'conexao') {
        form.elements.password.value = ''; form.elements.password.placeholder = 'Deixe em branco para manter a senha salva';
        document.querySelector('#inventory-panel').hidden = true; document.querySelector('#inventory-hint').hidden = false;
      }
      if (name === 'sincronizar') {
        document.querySelector('#sync-status').textContent = result.message;
        document.querySelector('#last-sync').textContent = new Date().toLocaleString('pt-BR');
        await loadInventory();
      }
    } catch (error) { window.notify(error.message || 'Não foi possível concluir a operação.', 'error'); }
    finally { busy = false; form.setAttribute('aria-busy', 'false'); }
  }
  form.addEventListener('submit', event => { event.preventDefault(); action('conexao'); });
  document.querySelectorAll('[data-action]').forEach(button => button.addEventListener('click', () => action(button.dataset.action)));
  const time = document.querySelector('#last-sync');
  if (time.dataset.timestamp) time.textContent = new Date(Number(time.dataset.timestamp)).toLocaleString('pt-BR');
  if (document.querySelector('.settings-page').dataset.synced === 'true') loadInventory().catch(error => window.notify(error.message, 'error'));
});
