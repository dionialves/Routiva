(() => {
  'use strict';
  const token = document.querySelector('meta[name="_csrf"]')?.content;
  const header = document.querySelector('meta[name="_csrf_header"]')?.content;
  if (!token || !header) return;
  const original = window.fetch.bind(window);
  window.fetch = async (input, options = {}) => {
    const url = new URL(input instanceof Request ? input.url : input, window.location.href);
    const method = (options.method || (input instanceof Request ? input.method : 'GET')).toUpperCase();
    if (url.origin === window.location.origin && !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)) {
      const headers = new Headers(options.headers || (input instanceof Request ? input.headers : undefined));
      headers.set(header, token);
      options = { ...options, headers };
    }
    const response = await original(input, options);
    if (response.status === 401 && url.origin === window.location.origin) window.location.assign('/login');
    return response;
  };
  document.addEventListener('htmx:configRequest', event => { event.detail.headers[header] = token; });
  document.addEventListener('submit', event => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement) || form.method.toLowerCase() === 'get'
        || new URL(form.action, window.location.href).origin !== window.location.origin) return;
    if (!form.querySelector('input[name="_csrf"]')) {
      const field = document.createElement('input');
      field.type = 'hidden'; field.name = '_csrf'; field.value = token; form.append(field);
    }
  }, true);
})();
