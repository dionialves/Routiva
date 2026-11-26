(() => {
  'use strict';
  const availabilityLabels = { available: 'SNMP disponível', unavailable: 'SNMP indisponível',
    unknown: 'SNMP desconhecido', none: 'Sem interface SNMP', disabled: 'Não monitorado' };
  const connectionLabels = { NOT_CONFIGURED: 'Não configurado', ACTIVE: 'Ativa', ERROR: 'Erro' };
  const connectionStatus = host => Object.hasOwn(connectionLabels, host.connectionStatus) ? host.connectionStatus : 'NOT_CONFIGURED';
  const matchesConnection = (host, status) => !status || connectionStatus(host) === status;
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
    } else if (key === 'backup') {
      order = Number(a.backupEnabled === true) - Number(b.backupEnabled === true);
    } else if (key === 'items' || key === 'triggers') {
      order = (a[key === 'items' ? 'monitoredItems' : 'monitoredTriggers'] || 0)
        - (b[key === 'items' ? 'monitoredItems' : 'monitoredTriggers'] || 0);
    } else {
      const value = host => key === 'name' ? nameOf(host) : key === 'templates'
        ? host.templates.map(template => template.name).join(', ') : key === 'status'
          ? (host.active ? 'Ativo' : 'Inativo') : key === 'connection' ? connectionLabels[connectionStatus(host)] : availabilityLabels[host.snmpAvailability] || availabilityLabels.unknown;
      order = value(a).localeCompare(value(b), 'pt-BR', { numeric: true, sensitivity: 'base' });
    }
    return direction * order || nameOf(a).localeCompare(nameOf(b), 'pt-BR', { numeric: true, sensitivity: 'base' })
      || String(a.id).localeCompare(String(b.id), 'en', { numeric: true });
  }
  if (typeof module !== 'undefined' && module.exports) module.exports = { compareHosts, ipValue, connectionStatus, matchesConnection };
  if (typeof document === 'undefined') return;
  const $ = id => document.getElementById(id);
  const form = $('host-form'), dialog = $('host-dialog');
  if (!form || window.HostConfiguration) return;
  const hasList = Boolean($('hosts-rows'));
  const field = name => form.elements.namedItem(name);
  const filterStorageKey = 'routiva.hosts.filters';
  let savedFilters = null;
  try {
    const saved = JSON.parse(localStorage.getItem(filterStorageKey));
    if (saved && typeof saved === 'object' && !Array.isArray(saved)) savedFilters = saved;
  } catch {}
  if (hasList && savedFilters) {
    if (typeof savedFilters.ip === 'string') $('host-search').value = savedFilters.ip;
    if ([...$('host-filter-connection').options].some(option => option.value === savedFilters.connection)) {
      $('host-filter-connection').value = savedFilters.connection;
    }
  }
  function filtersChanged() {
    try {
      localStorage.setItem(filterStorageKey, JSON.stringify({
        groups: [...$('incident-filter-group').selectedOptions].map(option => option.value),
        hosts: [...$('incident-filter-host').selectedOptions].map(option => option.value),
        connection: $('host-filter-connection').value,
        ip: $('host-search').value
      }));
    } catch {}
    page = 0;
    render();
  }

  const discardDialog = $('host-discard-dialog'), deleteDialog = $('host-delete-dialog');
  let passwordConfigured = false;
  let baseline = '', closeTrigger, authBaseline = '', terminalBaseline = '', backupBaseline = '', authLoaded = false, activeTab = 'general';
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
    if (!hasList) return;
    const query = $('host-search').value.trim().toLowerCase();
    const groups = new Set([...$('incident-filter-group').selectedOptions].map(option => option.value));
    const selectedHosts = new Set([...$('incident-filter-host').selectedOptions].map(option => option.value));
    const matches = hosts.filter(host => (!groups.size || host.groups.some(group => groups.has(group.id)))
      && (!selectedHosts.size || selectedHosts.has(host.id))
      && matchesConnection(host, $('host-filter-connection').value)
      && (host.ip || '').toLowerCase().includes(query))
      .sort((a, b) => compareHosts(a, b, sortKey, direction));
    page = Math.min(page, Math.max(0, Math.ceil(matches.length / 50) - 1));
    const start = page * 50, visible = matches.slice(start, start + 50);
    $('hosts-rows').replaceChildren(...visible.map(host => {
      const row = document.createElement('tr'), heading = document.createElement('th');
      heading.scope = 'row';
      if (options) {
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
        connectionLabels[connectionStatus(host)], availabilityLabels[host.snmpAvailability] || availabilityLabels.unknown];
      for (const [index, value] of values.entries()) {
        const cell = document.createElement('td');
        cell.textContent = value == null ? '—' : String(value);
        if (index === 3) cell.title = host.templates.map(template => template.name).join('\n') || 'Sem templates';
        if (index === 4) cell.dataset.hostStatus = host.active ? 'active' : 'inactive';
        if (index === 5) cell.dataset.connectionStatus = connectionStatus(host);
        if (index === 6) {
          cell.textContent = 'SNMP';
          cell.dataset.snmpAvailability = host.snmpAvailability || 'unknown';
          cell.title = value;
          cell.setAttribute('aria-label', value);
        }
        row.append(cell);
      }
      const backupCell = document.createElement('td');
      const enabled = host.backupEnabled === true;
      const label = enabled ? 'Backup ativo' : 'Backup desativado';
      backupCell.dataset.backupEnabled = String(enabled);
      const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      icon.classList.add('ui-icon');
      icon.setAttribute('viewBox', '0 0 24 24');
      icon.setAttribute('role', 'img');
      icon.setAttribute('aria-label', label);
      const use = document.createElementNS(icon.namespaceURI, 'use');
      use.setAttribute('href', enabled ? '/icons.svg#check' : '/icons.svg#close');
      icon.append(use);
      backupCell.title = label;
      backupCell.append(icon);
      row.insertBefore(backupCell, row.lastElementChild);
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
    if (!hasList) return;
    if (loading) return;
    loading = true; $('host-new').disabled = true;
    $('hosts-state').textContent = 'Carregando hosts…';
    try {
      const [rows, choices] = await Promise.all([request(), request('/options')]);
      hosts = rows; options = choices; setupFilters(); render(); $('host-new').disabled = false;
      const wanted = new URLSearchParams(location.search).get('host');
      if (wanted && !dialog.open) {
        const host = hosts.find(h => h.id === wanted);
        if (host) { open(host, $('host-new')); tab(new URLSearchParams(location.search).get('tab') === 'general' ? 'general' : 'auth'); history.replaceState(null, '', location.pathname); }
      }
    } catch (error) { $('hosts-state').textContent = error.message; }
    finally { loading = false; }
  }
  function setupFilters() {
    const groups = [...new Map(hosts.flatMap(host => host.groups).map(group => [group.id, group])).values()];
    const hostChoices = hosts.map(host => ({ id: host.id, name: nameOf(host) }));
    for (const [kind, choices, allLabel, selectedLabel] of [
      ['filter-group', groups, 'Todos os grupos', 'grupos selecionados'],
      ['filter-host', hostChoices, 'Todos os hosts', 'hosts selecionados']
    ]) {
      const select = $(`incident-${kind}`);
      const stored = savedFilters?.[kind === 'filter-group' ? 'groups' : 'hosts'];
      const selected = new Set(Array.isArray(stored) ? stored : [...select.selectedOptions].map(option => option.value));
      pickers[kind]?.dispose();
      choices.sort((a, b) => a.name.localeCompare(b.name, 'pt-BR'));
      select.replaceChildren(...choices.map(choice => new Option(choice.name, choice.id, false, selected.has(choice.id))));
      $(`incident-${kind}-options`).replaceChildren();
      pickers[kind] = window.setupMultiPicker(kind, select, allLabel, filtersChanged, {
        allText: kind === 'filter-group' ? 'Selecionar todos os grupos' : 'Selecionar todos os hosts',
        selectedLabel, preserveSelection: true
      });
      select.onchange = () => { pickers[kind](); filtersChanged(); };
    }
    savedFilters = null;
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
        menu.querySelector('summary').removeAttribute('aria-invalid');
      }
    }, {
      allText: groups ? 'Selecionar todos os grupos' : 'Selecionar todos os templates',
      selectedLabel: groups ? 'grupos selecionados' : 'templates selecionados', preserveSelection: true
    });
  }
  const selected = kind => [...$(`incident-${kind}`).selectedOptions].map(option => option.value).sort();
  function readInput() {
    const input = Object.fromEntries(['hostname', 'visibleName', 'description', 'ip', 'dns', 'port', 'community'].map(name => [name, field(name).value]));
    return Object.assign(input, { useIp: field('useIp').value === 'true', snmpVersion: Number(field('snmpVersion').value),
      active: field('active').checked, groupIds: selected('host-group'), templateIds: selected('host-template') });
  }
  function updateAuthFields() {
    const manufacturer = $('host-auth-manufacturer').value;
    const mikrotik = manufacturer === 'MikroTik';
    const mimosa = manufacturer === 'Mimosa';
    $('host-auth-test').disabled = !authLoaded || !current || !manufacturer;
    $('host-auth-ports').classList.toggle('host-auth-ports-mikrotik', mikrotik);
    $('host-auth-port-label').textContent = mimosa ? 'Porta HTTPS' : 'Porta API';
    $('host-auth-port-row').hidden = !mikrotik && !mimosa;
    $('host-auth-port').disabled = !mikrotik && !mimosa;
    $('host-auth-tls-row').hidden = !mikrotik;
    $('host-auth-tls').disabled = !mikrotik;
    for (const protocol of ['ssh', 'telnet']) {
      $(`host-${protocol}-port-row`).hidden = mimosa;
      $(`host-${protocol}-port`).disabled = mimosa;
    }
    $('host-auth-login-field').hidden = mimosa;
    if (mimosa) $('host-auth-login').value = 'configure';
  }
  function updatePasswordPlaceholder(configured) {
    passwordConfigured = configured;
    $('host-auth-password').placeholder = configured ? '*'.repeat(22) : '';
    $('host-auth-password-help').textContent = configured ? 'Deixe em branco para usar a senha cadastrada ao testar ou salvar.' : 'Informe a senha do equipamento.';
  }
  function hideAuthPassword() {
    $('host-auth-password').type = 'password';
    $('host-auth-password-toggle').setAttribute('aria-label', 'Mostrar senha');
    $('host-auth-password-toggle').setAttribute('aria-pressed', 'false');
    $('host-auth-password-toggle').querySelector('use').setAttribute('href', '/icons.svg#eye');
  }
  function authInput() {
    const manufacturer = $('host-auth-manufacturer').value;
    return { manufacturer, login: $('host-auth-login').value,
      password: $('host-auth-password').value || undefined,
      port: ['MikroTik', 'Mimosa'].includes(manufacturer) ? Number($('host-auth-port').value) : null,
      tls: manufacturer === 'Mimosa' || (manufacturer === 'MikroTik' && $('host-auth-tls').checked) };
  }
  function tab(name, focus = false) {
    $('host-edit-content').scrollTop = 0;
    activeTab = name;
    $('host-auth-test').hidden = name !== 'auth';
    for (const key of ['general', 'auth']) {
      const button = $(`host-${key}-tab`);
      button.setAttribute('aria-selected', String(key === name)); button.tabIndex = key === name ? 0 : -1;
      $(`host-${key}`).hidden = key !== name;
      if (focus && key === name) button.focus();
    }
    $('host-save').disabled = busy || !authLoaded;
    $('host-save-label').textContent = 'Salvar';
  }
  for (const name of ['general', 'auth']) {
    $(`host-${name}-tab`).addEventListener('click', () => tab(name));
    $(`host-${name}-tab`).addEventListener('keydown', event => {
      if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
        event.preventDefault(); tab(event.key === 'Home' ? 'general' : event.key === 'End' ? 'auth' : activeTab === 'auth' ? 'general' : 'auth', true);
      }
    });
  }
  async function loadAuth(host) {
    authLoaded = false;
    $('host-auth-fields').disabled = true; $('host-auth-test').disabled = true;
    $('host-backup-fields').disabled = true;
    tab(activeTab);
    try {
      const [c, backup] = host ? await Promise.all([
        request('/' + host.id + '/authentication'), request('/' + host.id + '/backup'),
      ]) : [{ sshPort: 22, telnetPort: 23 }, { enabled: false, retention: 5 }];
      if (current !== host || !dialog.open) return;
      for (const name of ['manufacturer', 'login']) $(`host-auth-${name}`).value = c[name] || '';
      $('host-auth-password').value = ''; $('host-auth-port').value = c.port || (c.manufacturer === 'Mimosa' ? 443 : 8728);
      $('host-auth-tls').checked = c.tls;
      updatePasswordPlaceholder(c.passwordConfigured);
      $('host-ssh-port').value = c.sshPort;
      $('host-telnet-port').value = c.telnetPort;
      $('host-backup-enabled').checked = backup.enabled;
      $('host-backup-retention').value = backup.retention;
      backupBaseline = JSON.stringify(backupInput());
      terminalBaseline = JSON.stringify(terminalPorts());
      authLoaded = true;
      $('host-auth-fields').disabled = false; updateAuthFields();
      authBaseline = JSON.stringify(authInput());
      $('host-backup-fields').disabled = false; tab(activeTab);
    } catch (error) { if (current === host && dialog.open) window.notify?.(error.message, 'error'); }
  }
  $('host-auth-manufacturer').addEventListener('change', () => {
    const manufacturer = $('host-auth-manufacturer').value;
    const mimosa = manufacturer === 'Mimosa';
    if (!mimosa && $('host-auth-login-field').hidden) $('host-auth-login').value = '';
    if (!manufacturer) {
      $('host-auth-login').value = '';
      $('host-auth-password').value = '';
      updatePasswordPlaceholder(false);
      hideAuthPassword();
    }
    $('host-auth-tls').checked = mimosa;
    $('host-auth-port').value = mimosa ? 443 : 8728;
    updateAuthFields();
  });
  $('host-auth-password-toggle').addEventListener('click', event => {
    const button = event.currentTarget, password = $('host-auth-password');
    const visible = password.type === 'password';
    password.type = visible ? 'text' : 'password';
    button.setAttribute('aria-label', visible ? 'Ocultar senha' : 'Mostrar senha');
    button.setAttribute('aria-pressed', String(visible));
    button.querySelector('use').setAttribute('href', visible ? '/icons.svg#eye-off' : '/icons.svg#eye');
  });
  $('host-auth-tls').addEventListener('change', () => {
    if (['8728', '8729'].includes($('host-auth-port').value)) $('host-auth-port').value = $('host-auth-tls').checked ? 8729 : 8728;
  });
  async function refreshConnectionStatus(host) {
    if (!hasList) return;
    try {
      const rows = await request();
      const updated = rows.find(row => row.id === host.id);
      if (!updated) return;
      host.connectionStatus = updated.connectionStatus;
      const row = hosts.find(row => row.id === host.id);
      if (row) row.connectionStatus = updated.connectionStatus;
      render();
    } catch { /* Keep the original authentication result visible. */ }
  }
  function validateAuthentication(input, testOnly) {
    const credentials = [
      ['host-auth-manufacturer', 'Fabricante', input.manufacturer],
      ['host-auth-login', 'Usuário', input.login.trim()],
      ['host-auth-password', 'Senha', input.password || (input.manufacturer && passwordConfigured)],
    ];
    const checks = testOnly || credentials.some(([, , value]) => value)
      ? credentials.filter(([, , value]) => !value).map(([id, label]) => [id, `${label}: preencha este campo.`])
      : [];
    for (const id of ['host-auth-port', 'host-ssh-port', 'host-telnet-port', ...(testOnly ? [] : ['host-backup-retention'])]) {
      const control = $(id);
      if (control.willValidate && !control.checkValidity()) {
        checks.push([id, `${control.labels[0].textContent}: ${control.validationMessage}`]);
      }
    }
    if (!checks.length) return true;
    tab('auth');
    for (const [id] of checks) $(id).setAttribute('aria-invalid', 'true');
    $(checks[0][0]).focus();
    window.notify?.(checks.map(([, message]) => message).join(' '), 'error');
    return false;
  }
  function authenticationInput() {
    return { ...authInput(), ...terminalPorts() };
  }
  $('host-auth-test').addEventListener('click', async () => {
    if (busy || !authLoaded || !current) return;
    const input = authenticationInput();
    clearErrors();
    if (!validateAuthentication(input, true)) return;
    const host = current;
    setBusy(true, 'test');
    try {
      const result = await request('/' + host.id + '/authentication/test', 'POST', input);
      window.notify?.(result.message, result.success ? 'success' : 'error');
      if (!result.success) await refreshConnectionStatus(host);
    } catch (error) {
      window.notify?.(error.message, 'error');
      await refreshConnectionStatus(host);
    } finally { setBusy(false); }
  });
  async function saveAuthentication(host, input) {
    const result = await request('/' + host.id + '/authentication', 'PUT', input);
    $('host-auth-password').value = '';
    updatePasswordPlaceholder(result.passwordConfigured);
    hideAuthPassword();
    authBaseline = JSON.stringify(authInput());
    terminalBaseline = JSON.stringify(terminalPorts());
    host.connectionStatus = !result.manufacturer ? 'NOT_CONFIGURED' : result.status === 'ACTIVE' ? 'ACTIVE' : 'ERROR';
    const row = hosts.find(item => item.id === host.id);
    if (row) row.connectionStatus = host.connectionStatus;
    render();
    return result;
  }
  function terminalPorts() {
    return { sshPort: $('host-ssh-port').valueAsNumber, telnetPort: $('host-telnet-port').valueAsNumber };
  }
  function backupInput() {
    return { enabled: $('host-backup-enabled').checked, retention: $('host-backup-retention').valueAsNumber };
  }
  async function saveBackup(host, backup) {
    const saved = await request('/' + host.id + '/backup', 'PUT', backup);
    backupBaseline = JSON.stringify({ enabled: saved.enabled, retention: saved.retention });
    host.backupEnabled = saved.enabled;
    const row = hosts.find(item => item.id === host.id);
    if (row) row.backupEnabled = saved.enabled;
    render();
  }
  function pendingAuthChanges() {
    return authLoaded && (JSON.stringify(authInput()) !== authBaseline || JSON.stringify(terminalPorts()) !== terminalBaseline
      || JSON.stringify(backupInput()) !== backupBaseline);
  }
  const isDirty = () => JSON.stringify(readInput()) !== baseline || pendingAuthChanges();
  function clearErrors() {
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
    }
  }
  function validate() {
    clearErrors();
    let firstInvalid = null;
    const messages = [];
    for (const control of $('host-general').querySelectorAll('input, select')) {
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
        messages.push(`${control.labels[0]?.textContent.replace('*', '').trim() || control.name}: ${message}`);
        firstInvalid ||= control;
      }
    }
    const groupSummary = $('incident-host-group-picker').querySelector('summary');
    groupSummary.removeAttribute('aria-invalid');
    if (!selected('host-group').length) {
      messages.push('Selecione pelo menos um grupo.');
      groupSummary.setAttribute('aria-invalid', 'true'); firstInvalid ||= groupSummary;
    }
    if (firstInvalid) { tab('general'); firstInvalid.focus(); }
    if (messages.length) window.notify?.(messages.join(' '), 'error');
    return !firstInvalid;
  }
  function open(host, trigger) {
    if (busy || !options) return;
    current = host; opener = trigger; form.reset(); hideAuthPassword(); clearErrors();
    $('host-dialog-title').textContent = host ? 'Editar host' : 'Novo host';
    $('host-dialog-description').textContent = host ? 'Altere os dados do host e salve as alterações no Zabbix.'
      : 'O novo host será cadastrado no Zabbix ao salvar.';
    $('host-delete').hidden = !host || !host.editable;
    $('host-general').querySelectorAll('input, select, textarea, button').forEach(control => { control.disabled = Boolean(host && !host.editable); });
    if (host) {
      for (const name of ['hostname', 'visibleName', 'description', 'ip', 'dns', 'port', 'snmpVersion', 'community']) field(name).value = host[name] ?? '';
      field('useIp').value = String(host.useIp); field('active').checked = host.active;
    }
    picker('host-group', options.groups, host?.groups || []);
    picker('host-template', options.templates, host?.templates || []);
    endpointRequired(); baseline = JSON.stringify(readInput()); $('host-edit-content').scrollTop = 0; dialog.showModal(); tab('general'); loadAuth(host); field('hostname').focus();
  }
  function closeNow() { dialog.close(); $('host-auth-password').value = ''; updatePasswordPlaceholder(false); hideAuthPassword(); authLoaded = false; opener?.focus(); }
  function requestClose() {
    if (busy) return;
    if (isDirty()) {
      closeTrigger = document.activeElement; discardDialog.showModal(); $('host-discard-cancel').focus();
    } else closeNow();
  }
  function setBusy(value, operation = 'save') {
    busy = value; form.setAttribute('aria-busy', String(value)); deleteDialog.setAttribute('aria-busy', String(value));
    for (const control of form.querySelectorAll('input, select, textarea, button')) control.disabled = value;
    for (const details of form.querySelectorAll('details')) { details.inert = value; if (value) details.open = false; }
    for (const control of deleteDialog.querySelectorAll('button')) control.disabled = value;
    for (const [id, action] of [['host-save', 'save'], ['host-auth-test', 'test'], ['host-delete-confirm', 'delete']]) {
      const button = $(id);
      const waiting = value && operation === action;
      button.classList.toggle('system-button-loading', waiting);
      button.setAttribute('aria-busy', String(waiting));
    }
    if (!value) { if (current && !current.editable) $('host-general').querySelectorAll('input, select, textarea, button').forEach(control => { control.disabled = true; }); $('host-auth-fields').disabled = !authLoaded; $('host-auth-test').disabled = !authLoaded; $('host-backup-fields').disabled = !authLoaded; updateAuthFields(); tab(activeTab); }
  }
  document.querySelectorAll('[data-host-sort]').forEach(button => {
    button.addEventListener('click', () => {
      const key = button.dataset.hostSort;
      direction = key === sortKey ? -direction : 1; sortKey = key; page = 0; render();
    });
  });
  $('host-new')?.addEventListener('click', event => open(null, event.currentTarget));
  $('host-cancel').addEventListener('click', requestClose);
  $('host-close').addEventListener('click', requestClose);
  dialog.addEventListener('cancel', event => {
    // First Escape closes a picker; a subsequent Escape closes the dialog.
    if (busy || [...form.querySelectorAll('details')].some(menu => menu.open) || event.defaultPrevented) {
      event.preventDefault(); return;
    }
    event.preventDefault(); requestClose();
  });
  $('host-search')?.addEventListener('input', filtersChanged);
  $('host-filter-connection')?.addEventListener('change', filtersChanged);
  $('hosts-prev')?.addEventListener('click', () => { page--; render(); });
  $('hosts-next')?.addEventListener('click', () => { page++; render(); });
  field('useIp').addEventListener('change', endpointRequired);
  form.querySelectorAll('[data-host-endpoint]').forEach(button => {
    button.addEventListener('click', () => {
      if (busy) return;
      field('useIp').value = button.dataset.hostEndpoint;
      endpointRequired();
    });
  });
  form.addEventListener('input', event => {
    event.target.removeAttribute('aria-invalid');
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
    $('host-delete-name').textContent = nameOf(current);
    deleteDialog.showModal(); $('host-delete-cancel').focus();
  });
  const cancelDelete = () => { if (!busy) { deleteDialog.close(); $('host-delete').focus(); } };
  $('host-delete-cancel').addEventListener('click', cancelDelete);
  deleteDialog.addEventListener('cancel', event => { event.preventDefault(); cancelDelete(); });
  $('host-delete-confirm').addEventListener('click', async () => {
    if (busy || !current) return;
    const id = current.id; setBusy(true, 'delete');
    let deleted = false;
    try {
      await request('/' + id, 'DELETE', { revision: current.revision, confirmed: true });
      deleted = true;
    } catch (error) { window.notify?.(error.message, 'error'); }
    finally { setBusy(false); }
    if (deleted) {
      deleteDialog.close(); closeNow(); hosts = hosts.filter(host => host.id !== id); render();
      $('host-new')?.focus(); window.notify?.('Host excluído do Zabbix.'); await load();
    }
  });
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (busy || !authLoaded) return;
    clearErrors();
    const general = readInput();
    const generalChanged = !current || (current.editable && JSON.stringify(general) !== baseline);
    const auth = authenticationInput();
    const backup = backupInput();
    const backupChanged = JSON.stringify(backup) !== backupBaseline;
    if ((generalChanged && !validate()) || !validateAuthentication(auth, false)) return;

    setBusy(true);
    let completed = false;
    let generalSaved = false;
    try {
      if (generalChanged) {
        const result = await request(current ? '/' + current.id : '', current ? 'PUT' : 'POST',
          { ...general, revision: current?.revision });
        current = { ...current, ...general, id: result.id, revision: result.revision, editable: true };
        baseline = JSON.stringify(general);
        generalSaved = true;
      }
      const result = await saveAuthentication(current, auth);
      if (backupChanged) await saveBackup(current, backup);
      const rejected = current.connectionStatus === 'ERROR';
      window.notify?.(rejected ? `Host salvo. ${result.message}` : 'Host salvo.', rejected ? 'error' : 'success');
      completed = true;
    } catch (error) {
      window.notify?.(generalSaved ? `Configuração do host salva. ${error.message}` : error.message, 'error');
    } finally { setBusy(false); }
    if (completed) { closeNow(); await load(); }
  });
  $('host-sync')?.addEventListener('click', async () => {
    $('host-sync').disabled = true;
    try { await request('/synchronize', 'POST'); await load(); }
    catch (error) { $('hosts-state').textContent = error.message; }
    finally { $('host-sync').disabled = false; }
  });
  window.HostConfiguration = {
    async open(hostId, trigger) {
      if (busy || dialog.open) return;
      try {
        const [rows, choices] = await Promise.all([request(), request('/options')]);
        const host = rows.find(item => String(item.id) === String(hostId));
        if (!host) throw new Error('Host não disponível para configuração.');
        hosts = rows; options = choices;
        open(host, trigger);
      } catch (error) { window.notify?.(error.message, 'error'); }
    }
  };
  load();
})();
