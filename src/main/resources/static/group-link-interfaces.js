'use strict';

const GroupLinkInterfaces = (() => {
  function validate(entries, hosts, groupId) {
    const seen = new Set();
    for (const entry of entries) {
      const host = hosts.find(host => host.id === entry.hostId);
      if (!host?.groups?.some(group => group.id === groupId)) return 'Existe um host que não pertence ao grupo selecionado. Revise a lista.';
      if (!entry.name || !MapSwitch.connectors[entry.connector] || (entry.capacity !== 'unknown' && !MapSwitch.portCapacities[entry.capacity]))
        return 'Revise a interface, o conector e a capacidade.';
      const key = JSON.stringify([entry.hostId, entry.name]);
      if (seen.has(key)) return 'Este host e esta interface já estão habilitados.';
      seen.add(key);
    }
    return '';
  }
  function seed(node, links) {
    if (node.linkInterfaces != null) return node.linkInterfaces.map(entry => ({...entry}));
    const result = new Map();
    links.forEach(link => {
      if (!link.connection) return;
      const side = link.from === node.id ? 'A' : link.to === node.id ? 'B' : null;
      if (!side) return;
      const hostId = link.connection['host' + side], name = link.connection['interface' + side];
      if (hostId && name) result.set(JSON.stringify([hostId, name]), {hostId, name, connector:'electrical', capacity:'unknown'});
    });
    return [...result.values()];
  }
  function create({hosts, discover}) {
    const get = id => document.getElementById('group-link-' + id);
    const root = document.getElementById('group-link-interfaces');
    const hostSelect = get('host'), interfaceSelect = get('interface'), retry = get('retry'), add = get('add');
    let entries = [], available = [], groupId = '', generation = 0, editingNode = null, links = [];
    const picker = window.setupGroupPicker(hostSelect, 'group-link-host-label', 'Pesquisar host do grupo',
      {listLabel:'Hosts do grupo', emptyText:'Nenhum host encontrado.'});
    function message(text) { get('status').textContent = text; }
    const interfacePicker = window.setupGroupPicker(interfaceSelect, 'group-link-interface-label', 'Pesquisar interface', {listLabel:'Interfaces', emptyText:'Nenhuma interface disponível.'});
    function feedback(text) { get('feedback').textContent = text; }
    function reset() {
      generation++; available = []; interfaceSelect.replaceChildren(new Option('Selecione um host para carregar as interfaces', ''));
      interfaceSelect.disabled = true; add.disabled = true; retry.hidden = true;
      interfaceSelect.removeAttribute('aria-busy'); interfacePicker.sync();
    }
    function select(value, choices, label, update) {
      const input = document.createElement('select'); input.setAttribute('aria-label', label);
      choices.forEach(([key, text]) => input.add(new Option(text, key)));
      input.value = value; input.onchange = () => update(input.value); return input;
    }
    function render() {
      get('rows').replaceChildren();
      entries.forEach((entry, index) => {
        const row = document.createElement('tr');
        const cell = content => { const td = document.createElement('td'); typeof content === 'string' ? td.textContent = content : td.append(content); row.append(td); };
        const hostName = hosts().find(host => host.id === entry.hostId)?.name || entry.hostId;
        cell(hostName); row.children[0].title = hostName;
        cell(entry.name);
        cell(select(entry.connector, Object.entries(MapSwitch.connectors).map(([key, info]) => [key, info.label]),
          'Conector de ' + entry.name, value => { entry.connector = value; }));
        cell(select(entry.capacity, [['unknown','Não identificada'], ...Object.entries(MapSwitch.portCapacities)],
          'Capacidade de ' + entry.name, value => { entry.capacity = value; }));
        const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'system-button system-button-danger';
        remove.innerHTML = '<svg class="system-button-icon" aria-hidden="true"><use href="/icons.svg#trash-2"/></svg>Remover'; remove.setAttribute('aria-label', 'Remover ' + entry.name + ' de ' + (hosts().find(h => h.id === entry.hostId)?.name || entry.hostId));
        remove.onclick = () => {
          const dependency = links.find(link => {
            const side = link.from === editingNode.id ? 'A' : link.to === editingNode.id ? 'B' : null;
            return side && link.connection?.['host' + side] === entry.hostId && link.connection?.['interface' + side] === entry.name;
          });
          if (dependency) { message('A interface é usada pelo enlace ' + (dependency.name || dependency.id || dependency.from + ' → ' + dependency.to) + '. Reconfigure ou remova o enlace primeiro.'); return; }
          entries.splice(index, 1); render(); message('Interface removida. Salve o elemento para aplicar.');
        };
        cell(remove); get('rows').append(row);
      });
      globalThis.TableState?.done('group-link-rows', 'Clique em Adicionar interface para incluir a primeira interface deste elemento.');
    }
    function closeEditor(focus = false) {
      reset(); hostSelect.value = ''; picker.sync(); get('editor').hidden = true;
      get('open').hidden = false; get('open').setAttribute('aria-expanded', 'false'); feedback('');
      if (focus) get('open').focus();
    }
    get('open').onclick = () => {
      closeEditor(); get('editor').hidden = false; get('open').hidden = true;
      get('open').setAttribute('aria-expanded', 'true');
      feedback(hostSelect.options.length > 1 ? 'As interfaces serão carregadas após a seleção do host.' : 'Nenhum host encontrado neste grupo.');
      hostSelect.parentElement.querySelector?.('button')?.focus();
    };
    get('cancel').onclick = () => closeEditor(true);
    interfaceSelect.onchange = () => {
      add.disabled = !available.some(row => row.name === interfaceSelect.value)
        || entries.some(entry => entry.hostId === hostSelect.value && entry.name === interfaceSelect.value);
      feedback(add.disabled ? 'Selecione uma interface disponível.' : 'Interface pronta para ser adicionada à lista.');
    };
    async function loadInterfaces() {
      reset();
      if (!hostSelect.value) { feedback('Selecione um host.'); return; }
      const token = generation, hostId = hostSelect.value;
      interfaceSelect.replaceChildren(new Option('Carregando interfaces...', ''));
      interfaceSelect.setAttribute('aria-busy', 'true'); interfacePicker.sync(); feedback('Carregando interfaces...');
      try {
        const rows = await discover(hostId);
        if (token !== generation || root.hidden || !root.closest('dialog').open) return;
        available = rows;
        interfaceSelect.replaceChildren(new Option('Selecione a interface', ''), ...rows.map(row => {
          const duplicate = entries.some(entry => entry.hostId === hostId && entry.name === row.name);
          const option = new Option(row.name + (duplicate ? ' · Já adicionada' : ''), row.name);
          option.disabled = duplicate; return option;
        }));
        interfaceSelect.disabled = !rows.length;
        feedback(rows.length ? 'Interfaces carregadas. Selecione uma interface.' : 'Nenhuma interface encontrada para este host.');
      } catch (error) {
        if (token === generation) { feedback('Não foi possível carregar as interfaces deste host.'); retry.hidden = false; }
      } finally {
        if (token === generation) { interfaceSelect.removeAttribute('aria-busy'); interfacePicker.sync(); }
      }
    }
    hostSelect.onchange = loadInterfaces;
    retry.onclick = loadInterfaces;
    root.closest('dialog').addEventListener?.('close', () => { generation++; });
    add.onclick = () => {
      const source = available.find(row => row.name === interfaceSelect.value);
      if (!source) return;
      const imported = MapSwitch.importInterfaces([source], []).ports[0];
      if (!imported) return;
      const entry = {hostId:hostSelect.value, name:source.name, connector:imported.connector, capacity:imported.capacity};
      const error = validate([...entries, entry], hosts(), groupId);
      if (error) { message(error); return; }
      entries.push(entry); render(); closeEditor(true); message('Interface habilitada. Salve o elemento para aplicar.');
    };
    return {
      open(node, currentLinks) { editingNode = node; links = currentLinks; entries = seed(node, links); closeEditor(); render(); message(''); },
      sync(id, visible) {
        root.hidden = !visible; groupId = id;
        const previous = hostSelect.value;
        hostSelect.replaceChildren(new Option('Selecione um host', ''), ...hosts()
          .filter(host => host.groups.some(group => group.id === id)).map(host => new Option(host.name + (host.ip ? ' · ' + host.ip : ''), host.id)));
        if ([...hostSelect.options].some(option => option.value === previous)) hostSelect.value = previous;
        picker.sync(); closeEditor();
      },
      value() { return entries.map(entry => ({...entry})); },
      validate(id) { const error = validate(entries, hosts(), id); message(error); return error; }
    };
  }
  return {create, validate, seed};
})();
if (typeof module !== 'undefined') module.exports = GroupLinkInterfaces;
