(() => {
  'use strict';
  const form = document.getElementById('login-form');
  if (!form) return;
  const get = id => document.getElementById(id);
  const username = get('username');
  const password = get('password');
  const arrow = get('identify-user');
  const clear = get('clear-user');
  const submit = get('login-submit');
  const error = get('login-error');
  let state = 'INITIAL';
  let setup = form.dataset.setup === 'true';
  let validated = setup;
  let codeValidated = false;
  const code = get('code');
  const codeArrow = get('validate-code');
  const confirmation = get('confirmation');
  const strongPassword = value => value.length >= 12 && value.length <= 128
    && /\p{Lu}/u.test(value) && /\p{Ll}/u.test(value) && /\p{Nd}/u.test(value)
    && /[^\p{L}\p{N}\s]/u.test(value);
  const isBusy = () => ['VALIDATING_USER', 'AUTHENTICATING', 'VALIDATING_CODE', 'SAVING_PASSWORD'].includes(state);
  let clearing = Promise.resolve();
  function render(next) {
    state = next;
    form.dataset.state = state;
    const busy = isBusy();
    form.setAttribute('aria-busy', String(busy));
    username.readOnly = validated || busy;
    arrow.hidden = validated;
    arrow.disabled = busy || !username.value.trim();
    arrow.classList.toggle('is-loading', state === 'VALIDATING_USER');
    clear.hidden = !validated;
    clear.disabled = busy;
    const ready = validated && (!setup || codeValidated);
    submit.hidden = !ready;
    submit.disabled = busy || (setup && (!strongPassword(password.value) || password.value !== confirmation.value));
    password.disabled = !ready || busy;
    confirmation.disabled = !ready || busy;
    get('password-fields').hidden = !ready;
    get('password-label').textContent = setup ? 'Nova senha' : 'Senha';
    get('password-requirements').hidden = !setup;
    get('confirmation-error').hidden = !setup || !confirmation.value || password.value === confirmation.value;
    code.readOnly = codeValidated || busy;
    codeArrow.hidden = codeValidated;
    codeArrow.disabled = busy || !code.value.trim();
    codeArrow.classList.toggle('is-loading', state === 'VALIDATING_CODE');
    get('code-check').hidden = !codeValidated;
    get('code-success').hidden = !codeValidated;
    code.classList.toggle('auth-code-valid', codeValidated);
    get('login-title').textContent = setup ? 'Definir senha' : 'Entrar';
    submit.classList.toggle('is-loading', ['AUTHENTICATING', 'SAVING_PASSWORD'].includes(state));
    submit.textContent = ['AUTHENTICATING', 'SAVING_PASSWORD'].includes(state) ? 'Entrando…' : setup ? 'Salvar senha e entrar' : 'Entrar';
    get('password-stage').hidden = !validated;
    get('setup-fields').hidden = !setup;
    get('confirmation-field').hidden = !setup || !codeValidated;
    password.required = ready;
    get('code').required = validated && setup;
    get('confirmation').required = ready && setup;
    password.autocomplete = setup ? 'new-password' : 'current-password';
    password.minLength = setup ? 12 : 0;
    get('login-help').textContent = validated ? setup ? codeValidated ? 'Código validado. Agora defina sua nova senha.' : 'Informe seu código de ativação para continuar.' : 'Informe sua senha para continuar.' : 'Informe seu usuário para continuar.';
  }
  async function post(path, data) {
    const response = await fetch(path, {
      method: 'POST', credentials: 'same-origin',
      headers: { Accept: 'application/json' }, body: data,
    });
    let result;
    try { result = await response.json(); }
    catch { throw new Error('Não foi possível continuar. Atualize a página e tente novamente.'); }
    if (!response.ok) {
      const failure = new Error(result.message || 'Não foi possível continuar. Tente novamente.');
      failure.reset = result.reset;
      failure.codeInvalid = result.codeInvalid;
      throw failure;
    }
    return result;
  }
  function reset() {
    validated = false;
    setup = false;
    codeValidated = false;
    get('code-error').hidden = true;
    confirmation.type = 'password';
    get('toggle-confirmation').setAttribute('aria-pressed', 'false');
    get('toggle-confirmation').setAttribute('aria-label', 'Mostrar confirmação de senha');
    get('confirmation-slash').hidden = true;
    username.value = '';
    password.value = '';
    get('code').value = '';
    get('confirmation').value = '';
    password.type = 'password';
    get('toggle-password').setAttribute('aria-pressed', 'false');
    get('toggle-password').setAttribute('aria-label', 'Mostrar senha');
    get('eye-slash').hidden = true;
    error.hidden = true;
    error.textContent = '';
    render('INITIAL');
    username.focus();
  }
  username.addEventListener('input', () => render('INITIAL'));
  clear.addEventListener('click', () => {
    // Reset locally immediately; the next identification also replaces the server challenge.
    const data = new FormData(form);
    reset();
    clearing = post('/login/limpar', data).catch(() => {});
  });
  get('toggle-password').addEventListener('click', () => {
    const visible = password.type === 'password';
    password.type = visible ? 'text' : 'password';
    get('toggle-password').setAttribute('aria-pressed', String(visible));
    get('toggle-password').setAttribute('aria-label', visible ? 'Ocultar senha' : 'Mostrar senha');
    get('eye-slash').hidden = !visible;
  });
  [code, password, confirmation].forEach(input => input.addEventListener('input', () => render(state)));
  get('toggle-confirmation').addEventListener('click', () => {
    const visible = confirmation.type === 'password';
    confirmation.type = visible ? 'text' : 'password';
    get('toggle-confirmation').setAttribute('aria-pressed', String(visible));
    get('toggle-confirmation').setAttribute('aria-label', visible ? 'Ocultar confirmação de senha' : 'Mostrar confirmação de senha');
    get('confirmation-slash').hidden = !visible;
  });
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (isBusy()) return;
    if (!validated && !username.value.trim()) return;
    error.hidden = true;
    const identifying = !validated;
    const validatingCode = validated && setup && !codeValidated;
    if (validatingCode && !code.value.trim()) return;
    if (setup && codeValidated && (!strongPassword(password.value) || password.value !== confirmation.value)) return;
    get('code-error').hidden = true;
    const data = new FormData(form);
    render(validatingCode ? 'VALIDATING_CODE' : identifying ? 'VALIDATING_USER' : setup ? 'SAVING_PASSWORD' : 'AUTHENTICATING');
    try {
      await clearing;
      const result = await post(validatingCode ? '/login/validar-codigo' : identifying ? '/login/usuario' : setup ? '/login/nova-senha' : '/login/senha', data);
      if (validatingCode) {
        codeValidated = true;
        render('CODE_VALIDATED');
        password.focus();
      } else if (identifying) {
        validated = true;
        setup = result.setup;
        username.value = result.username;
        render(setup ? 'WAITING_CODE' : 'USER_VALIDATED');
        (setup ? get('code') : password).focus();
      } else {
        window.location.assign(result.redirect);
      }
    } catch (failure) {
      if (failure.reset) reset();
      password.value = '';
      confirmation.value = '';
      if (failure.codeInvalid) codeValidated = false;
      render('ERROR');
      const target = setup && !codeValidated ? get('code-error') : error;
      target.textContent = failure.message;
      target.hidden = false;
      (setup && !codeValidated ? code : validated ? password : username).focus();
    }
  });
  render(setup ? 'WAITING_CODE' : 'INITIAL');
  if (setup) code.focus();
})();
