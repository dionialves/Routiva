(() => {
  'use strict';
  const $ = name => document.getElementById('ap-config-' + name);
  if (!$('dialog')) return;
  let hostId, busy = false, loaded = false;
  async function request(url, options = {}) {
    const response = await fetch(url, { cache: 'no-store', ...options });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || 'Não foi possível concluir a operação. Tente novamente.');
    return body;
  }
  function controls(pending) {
    busy = pending;
    $('fields').disabled = pending || !loaded;
    for (const name of ['save', 'test', 'delete']) $(name).disabled = pending || !loaded;
    for (const name of ['cancel', 'close', 'delete-confirm-button', 'delete-cancel']) {
      const control = $(name);
      if (control) control.disabled = pending;
    }
    $('dialog').setAttribute('aria-busy', String(pending));
  }
  function payload() {
    return { manufacturer: $('manufacturer').value, login: $('login').value,
      password: $('password').value, port: Number($('port').value), enabled: $('enabled').value === 'true', tls: $('tls').value === 'true' };
  }
  function changed(deleted = false) { document.dispatchEvent(new CustomEvent('ap-configuration-changed', { detail: { hostId, deleted } })); }
  window.openAccessPointConfiguration = async id => {
    if (busy) return;
    hostId = id; loaded = false;
    $('form').reset(); $('delete-confirm').hidden = true;
    $('password').value = ''; $('state').textContent = 'Carregando configuração…';
    controls(true); $('dialog').showModal();
    try {
      const config = await request('/api/ap/' + encodeURIComponent(id) + '/configuration');
      for (const name of ['manufacturer', 'name', 'ip', 'login']) $(name).value = config[name] || '';
      $('port').value = config.port || 8728; $('tls').value = String(config.tls || false); $('enabled').value = String(config.enabled);
      $('password').required = !config.passwordConfigured;
      $('password-help').textContent = config.passwordConfigured ? 'Senha cadastrada. Deixe em branco para manter a senha atual.' : 'Informe a senha do equipamento.';
      $('state').textContent = '';
      $('password').placeholder = config.passwordConfigured ? 'Digite a senha (opcional)' : 'Digite a senha';
      loaded = true;
    } catch (error) { $('state').textContent = ''; window.notify(error.message, 'error'); }
    finally { controls(false); }
  };
  $('tls').addEventListener('change', () => {
    if (['8728', '8729'].includes(String($('port').value))) $('port').value = $('tls').value === 'true' ? 8729 : 8728;
  });
  $('form').addEventListener('input', () => {
    if (!busy && loaded) $('state').textContent = 'Alterações não salvas. Ao habilitar e salvar, a conexão será testada novamente.';
  });
  async function submit(testOnly) {
    if (busy || !loaded || !$('form').reportValidity()) return;
    controls(true); $('delete-confirm').hidden = true;
    $('state').textContent = testOnly ? 'Testando conexão…' : payload().enabled ? 'Salvando configuração e validando conexão…' : 'Salvando configuração…';
    try {
      const result = await request('/api/ap/' + encodeURIComponent(hostId) + '/configuration' + (testOnly ? '/test' : ''),
        { method: testOnly ? 'POST' : 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload()) });
      if (testOnly) {
        $('state').textContent = '';
        window.notify(result.success ? 'Conexão validada com sucesso' : result.message, result.success ? 'success' : 'error');
      } else {
        $('state').textContent = '';
        $('password').value = ''; $('password').required = false;
        $('password-help').textContent = 'Senha cadastrada. Deixe em branco para manter a senha atual.';
        changed();
        if (result.status === 'ACTIVE') window.notify('Configuração salva e conexão validada com sucesso.');
        else if (result.status === 'INACTIVE') window.notify('Configuração salva. AP inativo.');
        else window.notify(result.message, 'error');
        $('password').placeholder = 'Digite a senha (opcional)';
      }
    } catch (error) { $('state').textContent = ''; window.notify(error.message, 'error'); }
    finally { controls(false); }
  }
  $('form').addEventListener('submit', event => { event.preventDefault(); submit(false); });
  $('test').addEventListener('click', () => submit(true));
  $('delete').addEventListener('click', () => { $('delete-confirm').hidden = false; $('delete-confirm-button').focus(); });
  $('delete-cancel').addEventListener('click', () => { $('delete-confirm').hidden = true; $('delete').focus(); });
  $('delete-confirm-button').addEventListener('click', async () => {
    if (busy || !loaded) return;
    controls(true); $('state').textContent = 'Excluindo cadastro de AP…';
    try {
      await request('/api/ap/' + encodeURIComponent(hostId), { method: 'DELETE' });
      changed(true); $('dialog').close();
    } catch (error) { $('state').textContent = ''; window.notify(error.message, 'error'); }
    finally { controls(false); }
  });
  for (const name of ['cancel', 'close']) $(name)?.addEventListener('click', () => { if (!busy) $('dialog').close(); });
  $('dialog').addEventListener('cancel', event => { if (busy) event.preventDefault(); });
  $('dialog').addEventListener('close', () => { $('password').value = ''; });
})();
