(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const dialog = $('user-dialog'), form = $('user-form');
  const normalize = value => String(value).normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  let users = [], current = null, busy = false, opener;
  async function request(path, method = 'GET', body) {
    const response = await fetch('/api/users' + path, {
      method, headers: { 'Content-Type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) })
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.message || 'Não foi possível concluir a operação.');
    return data;
  }
  function render() {
    const query = normalize($('user-search').value);
    const rows = users.filter(user => normalize(user.name).includes(query));
    $('users-rows').replaceChildren(...rows.map(user => {
      const row = document.createElement('tr'), name = document.createElement('th'), link = document.createElement('a');
      name.scope = 'row'; link.href = '#usuario-' + user.id; link.textContent = user.name;
      link.className = 'availability-host-name'; link.setAttribute('aria-haspopup', 'dialog');
      link.addEventListener('click', event => { event.preventDefault(); open(user, link); });
      name.append(link); row.append(name);
      for (const value of [user.username, user.role === 'ADMIN' ? 'Administrador' : 'Usuário normal',
        user.passwordResetRequired ? 'Cadastro de senha pendente' : 'Senha cadastrada',
        user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString('pt-BR') : 'Nunca acessou']) {
        const cell = document.createElement('td'); cell.textContent = value; row.append(cell);
      }
      return row;
    }));
    $('users-state').textContent = !users.length ? 'Nenhum usuário cadastrado.' : !rows.length ? 'Nenhum usuário encontrado.' : `${rows.length} usuário(s).`;
  }
  async function load() {
    $('users-retry').hidden = true;
    try { users = await request(''); render(); }
    catch (error) { $('users-state').textContent = error.message; $('users-retry').hidden = false; }
  }
  function open(user, trigger) {
    current = user; opener = trigger; form.reset();
    $('user-dialog-title').textContent = user ? 'Editar usuário' : 'Novo usuário';
    $('user-name').value = user?.name || ''; $('user-username').value = user?.username || '';
    $('user-role').value = user?.role || 'USER'; $('user-error').textContent = '';
    $('user-reset').hidden = !user; $('user-code-panel').hidden = true; $('user-code').textContent = '';
    $('user-code-status').textContent = ''; $('user-reset-confirm').hidden = true;
    $('user-save').hidden = false; $('user-cancel-label').textContent = 'Cancelar';
    for (const field of form.querySelectorAll('input, select')) field.disabled = false;
    dialog.showModal(); $('user-name').focus();
  }
  function close() {
    if (busy) return;
    dialog.close(); $('user-code').textContent = ''; opener?.focus();
  }
  function setBusy(value) {
    busy = value; form.setAttribute('aria-busy', String(value));
    for (const control of form.querySelectorAll('button, input, select')) control.disabled = value;
  }
  function showCode(data) {
    current = data.user;
    $('user-code').textContent = data.activationCode; $('user-code-panel').hidden = false;
    $('user-reset-confirm').hidden = true; $('user-reset').hidden = true; $('user-save').hidden = true;
    $('user-cancel-label').textContent = 'Fechar';
    for (const field of form.querySelectorAll('input, select')) field.disabled = true;
    $('user-code-panel').scrollIntoView({ block: 'nearest' });
  }
  $('user-new').addEventListener('click', event => open(null, event.currentTarget));
  $('user-cancel').addEventListener('click', close);
  dialog.addEventListener('cancel', event => { event.preventDefault(); close(); });
  dialog.addEventListener('close', () => { $('user-code').textContent = ''; });
  $('user-search').addEventListener('input', render); $('users-retry').addEventListener('click', load);
  $('user-reset').addEventListener('click', () => { $('user-reset-confirm').hidden = false; $('user-reset-confirm-button').focus(); });
  $('user-reset-cancel').addEventListener('click', () => { $('user-reset-confirm').hidden = true; $('user-reset').focus(); });
  form.addEventListener('submit', async event => {
    event.preventDefault(); if (busy) return;
    $('user-error').textContent = ''; setBusy(true);
    let result;
    try {
      result = await request(current ? '/' + current.id : '', current ? 'PUT' : 'POST', {
        name: $('user-name').value.trim(), username: $('user-username').value.trim(), role: $('user-role').value,
        version: current?.version
      });
      await load(); window.notify?.('Usuário salvo.');
    } catch (error) { $('user-error').textContent = error.message; }
    finally { setBusy(false); }
    if (result?.activationCode) showCode(result);
    else if (result) close();
  });
  $('user-reset-confirm-button').addEventListener('click', async () => {
    if (busy || !current) return;
    $('user-error').textContent = ''; setBusy(true);
    let result;
    try { result = await request('/' + current.id + '/reset-password', 'POST'); }
    catch (error) { $('user-error').textContent = error.message; }
    finally { setBusy(false); }
    if (result) {
      showCode(result);
      // A reset of the current administrator revokes its session too. Keep the code visible until closed.
      users = users.map(user => user.id === result.user.id ? result.user : user); render();
    }
  });
  $('user-copy-code').addEventListener('click', async () => {
    try { await navigator.clipboard.writeText($('user-code').textContent); $('user-code-status').textContent = 'Código copiado.'; }
    catch { $('user-code-status').textContent = 'Selecione o código e copie manualmente.'; }
  });
  load();
})();
