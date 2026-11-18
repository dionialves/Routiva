(() => {
  'use strict';
  const availabilityLabels = { available: 'SNMP disponível', unavailable: 'SNMP indisponível',
    unknown: 'SNMP desconhecido', none: 'Sem interface SNMP', disabled: 'Não monitorado' };
  const nameOf = host => host.visibleName || host.hostname;
  function ipValue(value) {
    const text = String(value || '').toLowerCase().split('%')[0];
    const ipv4 = value => {
      const parts = value.split('.');
      return parts.length === 4 && parts.every(p => /^\d{1,3}$/.test(p) && Number(p) <= 255)
        ? parts.map(Number) : null;
    };
    const v4 = ipv4(text);
    if (v4) return [4, v4.reduce((n, octet) => (n << 8n) + BigInt(octet), 0n)];
    if (!text.includes(':')) return null;
    let v6 = text;
    if (v6.includes('.')) {
      const colon = v6.lastIndexOf(':'), tail = ipv4(v6.slice(colon + 1));
      if (!tail) return null;
      v6 = v6.slice(0, colon + 1) + ((tail[0] << 8) + tail[1]).toString(16) + ':' + ((tail[2] << 8) + tail[3]).toString(16);
    }
    const halves = v6.split('::');
    if (halves.length > 2) return null;
    const left = halves[0] ? halves[0].split(':') : [];
    const right = halves.length === 2 && halves[1] ? halves[1].split(':') : [];
    const missing = 8 - left.length - right.length;
    if ((halves.length === 1 && missing !== 0) || (halves.length === 2 && missing < 1)) return null;
    const groups = [...left, ...Array(missing).fill('0'), ...right];
    if (!groups.every(group => /^[0-9a-f]{1,4}$/.test(group))) return null;
    return [6, groups.reduce((n, group) => (n << 16n) + BigInt(parseInt(group, 16)), 0n)];
  }
  function compareHosts(a, b, key, direction = 1) {
    let order = 0;
    if (key === 'interface') {
      const left = ipValue(a.ip), right = ipValue(b.ip);
      if (!left || !right) {
        if (Boolean(left) !== Boolean(right)) return left ? -1 : 1;
        order = String(a.ip || '').localeCompare(String(b.ip || ''), 'pt-BR', { numeric: true });
      } else order = left[0] - right[0] || (left[1] < right[1] ? -1 : left[1] > right[1] ? 1 : 0);
    } else if (key === 'items' || key === 'triggers') {
      order = (a[key === 'items' ? 'monitoredItems' : 'monitoredTriggers'] || 0)
        - (b[key === 'items' ? 'monitoredItems' : 'monitoredTriggers'] || 0);
    } else {
      const value = host => key === 'name' ? nameOf(host) : key === 'templates'
        ? host.templates.map(template => template.name).join(', ') : key === 'status'
          ? (host.active ? 'Ativo' : 'Inativo') : availabilityLabels[host.snmpAvailability] || availabilityLabels.unknown;
      order = value(a).localeCompare(value(b), 'pt-BR', { numeric: true, sensitivity: 'base' });
    }
    return direction * order || nameOf(a).localeCompare(nameOf(b), 'pt-BR', { numeric: true, sensitivity: 'base' })
      || String(a.id).localeCompare(String(b.id), 'en', { numeric: true });
  }
  if (typeof module !== 'undefined' && module.exports) module.exports = { compareHosts, ipValue };
  if (typeof document === 'undefined') return;
  const $ = id => document.getElementById(id);
  const form = $('host-form'), dialog = $('host-dialog');
  const field = name => form.elements.namedItem(name);
  const discardDialog = $('host-discard-dialog'), deleteDialog = $('host-delete-dialog');
  let baseline = '', closeTrigger;
  let hosts = [], options, current, opener, busy = false, loading = false, page = 0;
  let sortKey = 'name', direction = 1;
  const pickers = {};
  async function request(path = '', method = 'GET', body) {
    const response = await fetch('/api/host-configurations' + path, {
      method, headers: { 'Content-Type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) })
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.message || 'Não foi possível acessar o Zabbix.');
    return data;
  }
  function render() {
    const query = $('host-search').value.trim().toLowerCase();
    const groups = new Set([...$('incident-filter-group').selectedOptions].map(option => option.value));
    const selectedHosts = new Set([...$('incident-filter-host').selectedOptions].map(option => option.value));
    const matches = hosts.filter(host => (!groups.size || host.groups.some(group => groups.has(group.id)))
      && (!selectedHosts.size || selectedHosts.has(host.id))
      && (host.ip || '').toLowerCase().includes(query))
      .sort((a, b) => compareHosts(a, b, sortKey, direction));
    page = Math.min(page, Math.max(0, Math.ceil(matches.length / 50) - 1));
    const start = page * 50, visible = matches.slice(start, start + 50);
    $('hosts-rows').replaceChildren(...visible.map(host => {
      const row = document.createElement('tr'), heading = document.createElement('th');
      heading.scope = 'row';
      if (host.editable && options) {
        const link = document.createElement('a');
        link.href = '#host-dialog'; link.className = 'availability-host-name';
        link.textContent = nameOf(host);
        link.setAttribute('aria-haspopup', 'dialog'); link.setAttribute('aria-controls', 'host-dialog');
        link.addEventListener('click', event => { event.preventDefault(); open(host, link); });
        heading.append(link);
      } else {
        heading.textContent = nameOf(host);
        heading.title = 'Hosts descobertos e SNMPv3 devem ser editados no Zabbix.';
      }
      row.append(heading);
      const values = [host.monitoredItems, host.monitoredTriggers, host.ip || '—',
        host.templates.map(template => template.name).join(', ') || '—', host.active ? 'Ativo' : 'Inativo',
        availabilityLabels[host.snmpAvailability] || availabilityLabels.unknown];
      for (const [index, value] of values.entries()) {
        const cell = document.createElement('td');
        cell.textContent = value == null ? '—' : String(value);
        if (index === 3) cell.title = host.templates.map(template => template.name).join('\n') || 'Sem templates';
        if (index === 4) cell.dataset.hostStatus = host.active ? 'active' : 'inactive';
        if (index === 5) {
          cell.textContent = 'SNMP';
          cell.dataset.snmpAvailability = host.snmpAvailability || 'unknown';
          cell.title = value;
          cell.setAttribute('aria-label', value);
        }
        row.append(cell);
      }
      return row;
    }));
    document.querySelectorAll('[data-host-sort]').forEach(button => {
      button.closest('th').setAttribute('aria-sort', button.dataset.hostSort === sortKey
        ? (direction === 1 ? 'ascending' : 'descending') : 'none');
    });
    $('hosts-state').textContent = !hosts.length ? 'Nenhum host disponível para edição com as permissões da integração.'
      : !matches.length ? 'Nenhum host encontrado.' : 'Consulte e gerencie os hosts cadastrados.';
    $('hosts-range').textContent = `Exibindo ${matches.length ? start + 1 : 0}–${start + visible.length} de ${matches.length} registros`;
    $('hosts-prev').disabled = page === 0; $('hosts-next').disabled = start + 50 >= matches.length;
  }
  async function load() {
    if (loading) return;
    loading = true; $('host-new').disabled = true;
    $('hosts-state').textContent = 'Carregando hosts…';
    try {
      const [rows, choices] = await Promise.all([request(), request('/options')]);
      hosts = rows; options = choices; setupFilters(); render(); $('host-new').disabled = false;
    } catch (error) { $('hosts-state').textContent = error.message; }
    finally { loading = false; }
  }
  function setupFilters() {
    const groups = [...new Map(hosts.flatMap(host => host.groups).map(group => [group.id, group])).values()];
    const hostChoices = hosts.map(host => ({ id: host.id,
      name: host.visibleName && host.visibleName !== host.hostname
        ? `${host.visibleName} (${host.hostname})` : host.hostname }));
    for (const [kind, choices, allLabel, selectedLabel] of [
      ['filter-group', groups, 'Todos os grupos', 'grupos selecionados'],
      ['filter-host', hostChoices, 'Todos os hosts', 'hosts selecionados']
    ]) {
      const select = $(`incident-${kind}`);
      const selected = new Set([...select.selectedOptions].map(option => option.value));
      pickers[kind]?.dispose();
      choices.sort((a, b) => a.name.localeCompare(b.name, 'pt-BR'));
      select.replaceChildren(...choices.map(choice => new Option(choice.name, choice.id, false, selected.has(choice.id))));
      $(`incident-${kind}-options`).replaceChildren();
      pickers[kind] = window.setupMultiPicker(kind, select, allLabel, () => { page = 0; render(); }, {
        allText: kind === 'filter-group' ? 'Selecionar todos os grupos' : 'Selecionar todos os hosts',
        selectedLabel, preserveSelection: true
      });
      select.onchange = () => { pickers[kind](); page = 0; render(); };
    }
  }
  function picker(kind, available, selected) {
    pickers[kind]?.dispose();
    const select = $(`incident-${kind}`), values = new Set(selected.map(choice => choice.id));
    const merged = new Map([...available, ...selected].map(choice => [choice.id, choice]));
    select.replaceChildren(...[...merged.values()].map(choice => new Option(choice.name, choice.id, false, values.has(choice.id))));
    $(`incident-${kind}-options`).replaceChildren(); $(`incident-${kind}-search`).value = '';
    const menu = $(`incident-${kind}-picker`);
    menu.open = false;
    menu.onkeydown = event => { if (event.key === 'Escape' && menu.open) event.preventDefault(); };
    const groups = kind === 'host-group';
    pickers[kind] = window.setupMultiPicker(kind, select, groups ? 'Selecione os grupos' : 'Selecione os templates', () => {
      if (groups && select.selectedOptions.length) {
        $('host-groups-error').textContent = '';
        menu.querySelector('summary').removeAttribute('aria-invalid');
      }
    }, {
      allText: groups ? 'Selecionar todos os grupos' : 'Selecionar todos os templates',
      selectedLabel: groups ? 'grupos selecionados' : 'templates selecionados', preserveSelection: true
    });
  }
  const selected = kind => [...$(`incident-${kind}`).selectedOptions].map(option => option.value).sort();
  function readInput() {
    const input = Object.fromEntries(['hostname', 'visibleName', 'ip', 'dns', 'port', 'community'].map(name => [name, field(name).value]));
    return Object.assign(input, { useIp: field('useIp').value === 'true', snmpVersion: Number(field('snmpVersion').value),
      active: field('active').checked, groupIds: selected('host-group'), templateIds: selected('host-template') });
  }
  const isDirty = () => JSON.stringify(readInput()) !== baseline;
  function clearErrors() {
    $('host-error').textContent = ''; $('host-groups-error').textContent = '';
    form.querySelectorAll('.host-field-error').forEach(error => { error.textContent = ''; });
    form.querySelectorAll('[aria-invalid]').forEach(control => control.removeAttribute('aria-invalid'));
  }
  function endpointRequired() {
    field('ip').required = field('useIp').value === 'true';
    field('dns').required = field('useIp').value === 'false';
    $('host-ip-required').hidden = !field('ip').required;
    $('host-dns-required').hidden = !field('dns').required;
    $('host-ip-field').hidden = !field('ip').required;
    $('host-dns-field').hidden = !field('dns').required;
    form.querySelectorAll('[data-host-endpoint]').forEach(button => {
      button.setAttribute('aria-pressed', String(button.dataset.hostEndpoint === field('useIp').value));
    });
    for (const name of ['ip', 'dns']) {
      field(name).removeAttribute('aria-invalid');
      $(`host-${name}-error`).textContent = '';
    }
  }
  function validate() {
    clearErrors();
    let firstInvalid = null;
    for (const control of form.querySelectorAll('input, select')) {
      if (!control.willValidate) continue;
      let message = '';
      if (!control.validity.valid) {
        message = control.validity.valueMissing ? 'Preencha este campo.'
          : control.name === 'hostname' ? 'Use letras sem acentos, números, espaços, ponto, hífen ou sublinhado.'
            : control.name === 'port' ? 'Informe uma porta inteira entre 1 e 65535.' : 'Confira o valor informado.';
      } else if (control.name === 'ip' && control.required && !ipValue(control.value.trim())) {
        message = 'Informe um endereço IPv4 ou IPv6 válido.';
      } else if (control.name === 'dns' && control.required && /\s/.test(control.value.trim())) {
        message = 'Informe um nome DNS sem espaços.';
      }
      if (message) {
        control.setAttribute('aria-invalid', 'true');
        const error = form.querySelector(`[data-error-for="${control.name}"]`);
        if (error) error.textContent = message;
        firstInvalid ||= control;
      }
    }
    const groupSummary = $('incident-host-group-picker').querySelector('summary');
    groupSummary.removeAttribute('aria-invalid');
    groupSummary.setAttribute('aria-describedby', 'host-groups-error');
    if (!selected('host-group').length) {
      $('host-groups-error').textContent = 'Selecione pelo menos um grupo.';
      groupSummary.setAttribute('aria-invalid', 'true'); firstInvalid ||= groupSummary;
    }
    firstInvalid?.focus();
    return !firstInvalid;
  }
  function open(host, trigger) {
    if (busy || !options) return;
    current = host; opener = trigger; form.reset(); clearErrors();
    $('host-dialog-title').textContent = host ? 'Editar host' : 'Novo host';
    $('host-dialog-description').textContent = host ? 'As alterações serão aplicadas ao host no Zabbix ao salvar.'
      : 'O novo host será cadastrado no Zabbix ao salvar.';
    $('host-delete').hidden = !host;
    if (host) {
      for (const name of ['hostname', 'visibleName', 'ip', 'dns', 'port', 'snmpVersion', 'community']) field(name).value = host[name];
      field('useIp').value = String(host.useIp); field('active').checked = host.active;
    }
    picker('host-group', options.groups, host?.groups || []);
    picker('host-template', options.templates, host?.templates || []);
    endpointRequired(); baseline = JSON.stringify(readInput()); dialog.showModal(); field('hostname').focus();
  }
  function closeNow() { dialog.close(); opener?.focus(); }
  function requestClose() {
    if (busy) return;
    if (isDirty()) {
      closeTrigger = document.activeElement; discardDialog.showModal(); $('host-discard-cancel').focus();
    } else closeNow();
  }
  function setBusy(value, operation = 'save') {
    busy = value; form.setAttribute('aria-busy', String(value)); deleteDialog.setAttribute('aria-busy', String(value));
    for (const control of form.querySelectorAll('input, select, button')) control.disabled = value;
    for (const details of form.querySelectorAll('details')) { details.inert = value; if (value) details.open = false; }
    for (const control of deleteDialog.querySelectorAll('button')) control.disabled = value;
    $('host-save-label').textContent = value && operation === 'save' ? 'Salvando…' : 'Salvar no Zabbix';
    $('host-delete-label').textContent = value && operation === 'delete' ? 'Excluindo…' : 'Excluir no Zabbix';
  }
  document.querySelectorAll('[data-host-sort]').forEach(button => {
    button.addEventListener('click', () => {
      const key = button.dataset.hostSort;
      direction = key === sortKey ? -direction : 1; sortKey = key; page = 0; render();
    });
  });
  $('host-new').addEventListener('click', event => open(null, event.currentTarget));
  $('host-cancel').addEventListener('click', requestClose);
  $('host-close').addEventListener('click', requestClose);
  dialog.addEventListener('cancel', event => {
    // First Escape closes a picker; a subsequent Escape closes the dialog.
    if (busy || [...form.querySelectorAll('details')].some(menu => menu.open) || event.defaultPrevented) {
      event.preventDefault(); return;
    }
    event.preventDefault(); requestClose();
  });
  $('host-search').addEventListener('input', () => { page = 0; render(); });
  $('hosts-prev').addEventListener('click', () => { page--; render(); });
  $('hosts-next').addEventListener('click', () => { page++; render(); });
  field('useIp').addEventListener('change', endpointRequired);
  form.querySelectorAll('[data-host-endpoint]').forEach(button => {
    button.addEventListener('click', () => {
      if (busy) return;
      field('useIp').value = button.dataset.hostEndpoint;
      endpointRequired();
    });
  });
  form.addEventListener('input', event => {
    const name = event.target.name;
    event.target.removeAttribute('aria-invalid');
    const error = form.querySelector(`[data-error-for="${name}"]`);
    if (error) error.textContent = '';
  });
  window.addEventListener('beforeunload', event => {
    if (dialog.open && (busy || isDirty())) { event.preventDefault(); event.returnValue = ''; }
  });
  const cancelDiscard = () => { discardDialog.close(); closeTrigger?.focus(); };
  $('host-discard-cancel').addEventListener('click', cancelDiscard);
  discardDialog.addEventListener('cancel', event => { event.preventDefault(); cancelDiscard(); });
  $('host-discard-confirm').addEventListener('click', () => { discardDialog.close(); closeNow(); });
  $('host-delete').addEventListener('click', () => {
    if (busy || !current) return;
    $('host-delete-name').textContent = nameOf(current); $('host-delete-error').textContent = '';
    deleteDialog.showModal(); $('host-delete-cancel').focus();
  });
  const cancelDelete = () => { if (!busy) { deleteDialog.close(); $('host-delete').focus(); } };
  $('host-delete-cancel').addEventListener('click', cancelDelete);
  deleteDialog.addEventListener('cancel', event => { event.preventDefault(); cancelDelete(); });
  $('host-delete-confirm').addEventListener('click', async () => {
    if (busy || !current) return;
    const id = current.id; $('host-delete-error').textContent = ''; setBusy(true, 'delete');
    let deleted = false;
    try {
      await request('/' + id, 'DELETE', { revision: current.revision, confirmed: true });
      deleted = true;
    } catch (error) { $('host-delete-error').textContent = error.message; window.notify?.(error.message, 'error'); }
    finally { setBusy(false); }
    if (deleted) {
      deleteDialog.close(); closeNow(); hosts = hosts.filter(host => host.id !== id); render();
      $('host-new').focus(); window.notify?.('Host excluído do Zabbix.'); await load();
    }
  });
  form.addEventListener('submit', async event => {
    event.preventDefault(); if (busy || !validate()) return;
    const input = { ...readInput(), revision: current?.revision };
    setBusy(true);
    let saved = false;
    try {
      await request(current ? '/' + current.id : '', current ? 'PUT' : 'POST', input);
      saved = true;
    } catch (error) { $('host-error').textContent = error.message; window.notify?.(error.message, 'error'); }
    finally { setBusy(false); }
    if (saved) { closeNow(); window.notify?.('Host salvo no Zabbix.'); await load(); }
  });
  load();
})();
