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
    const hostSelect = get('host'), interfaceSelect = get('interface'), load = get('load'), add = get('add');
    let entries = [], available = [], groupId = '', generation = 0;
    const picker = window.setupGroupPicker(hostSelect, 'group-link-host-label', 'Pesquisar host do grupo',
      {listLabel:'Hosts do grupo', emptyText:'Nenhum host encontrado.'});
    function message(text) { get('status').textContent = text; }
    function reset() {
      generation++; available = []; interfaceSelect.replaceChildren(new Option('Selecione a interface', ''));
      interfaceSelect.disabled = true; add.disabled = true; load.disabled = !hostSelect.value;
      load.classList.remove('system-button-loading'); load.removeAttribute('aria-busy');
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
        cell(hosts().find(host => host.id === entry.hostId)?.name || entry.hostId);
        cell(entry.name);
        cell(select(entry.connector, Object.entries(MapSwitch.connectors).map(([key, info]) => [key, info.label]),
          'Conector de ' + entry.name, value => { entry.connector = value; }));
        cell(select(entry.capacity, [['unknown','Não identificada'], ...Object.entries(MapSwitch.portCapacities)],
          'Capacidade de ' + entry.name, value => { entry.capacity = value; }));
        const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'system-button system-button-danger';
        remove.textContent = 'Remover'; remove.setAttribute('aria-label', 'Remover ' + entry.name + ' de ' + (hosts().find(h => h.id === entry.hostId)?.name || entry.hostId));
        remove.onclick = () => { entries.splice(index, 1); render(); };
        cell(remove); get('rows').append(row);
      });
      if (!entries.length) {
        const row = document.createElement('tr'), cell = document.createElement('td'); cell.colSpan = 5;
        cell.textContent = 'Nenhuma interface habilitada para enlaces.'; row.append(cell); get('rows').append(row);
      }
    }
    hostSelect.onchange = () => { reset(); message(''); };
    interfaceSelect.onchange = () => { add.disabled = !interfaceSelect.value; };
    load.onclick = async () => {
      if (!hostSelect.value || load.disabled) return;
      const token = ++generation, hostId = hostSelect.value;
      load.disabled = true; add.disabled = true; interfaceSelect.disabled = true;
      load.classList.add('system-button-loading'); load.setAttribute('aria-busy', 'true'); message('Carregando interfaces do Zabbix…');
      try {
        const rows = await discover(hostId);
        if (token !== generation || root.hidden || !root.closest('dialog').open) return;
        available = rows;
        interfaceSelect.replaceChildren(new Option('Selecione a interface', ''), ...rows.map(row => new Option(row.name, row.name)));
        interfaceSelect.disabled = !rows.length;
        message(rows.length ? 'Selecione a interface e clique em Habilitar interface. Revise conector e capacidade na lista.' : 'Nenhuma interface encontrada no Zabbix.');
      } catch (error) { if (token === generation) message(error.message || 'Não foi possível consultar o Zabbix.'); }
      finally { if (token === generation) { load.disabled = false; load.classList.remove('system-button-loading'); load.removeAttribute('aria-busy'); } }
    };
    add.onclick = () => {
      const source = available.find(row => row.name === interfaceSelect.value);
      if (!source) return;
      const imported = MapSwitch.importInterfaces([source], []).ports[0];
      if (!imported) return;
      const entry = {hostId:hostSelect.value, name:source.name, connector:imported.connector, capacity:imported.capacity};
      const error = validate([...entries, entry], hosts(), groupId);
      if (error) { message(error); return; }
      entries.push(entry); render(); message('Interface habilitada. Salve o elemento para aplicar.');
    };
    return {
      open(node, links) { entries = seed(node, links); generation++; render(); message(''); },
      sync(id, visible) {
        root.hidden = !visible; groupId = id;
        const previous = hostSelect.value;
        hostSelect.replaceChildren(new Option('Selecione um host', ''), ...hosts()
          .filter(host => host.groups.some(group => group.id === id)).map(host => new Option(host.name + (host.ip ? ' · ' + host.ip : ''), host.id)));
        if ([...hostSelect.options].some(option => option.value === previous)) hostSelect.value = previous;
        picker.sync(); reset();
      },
      value() { return entries.map(entry => ({...entry})); },
      validate(id) { const error = validate(entries, hosts(), id); message(error); return error; }
    };
  }
  return {create, validate, seed};
})();
if (typeof module !== 'undefined') module.exports = GroupLinkInterfaces;
