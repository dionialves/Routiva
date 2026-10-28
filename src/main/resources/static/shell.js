document.addEventListener('DOMContentLoaded', () => {
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') document.querySelectorAll('.utility-menu[open]').forEach(menu => menu.removeAttribute('open'));
  });
  document.addEventListener('click', (event) => {
    document.querySelectorAll('.utility-menu[open]').forEach(menu => {
      if (!menu.contains(event.target)) menu.removeAttribute('open');
    });
  });
});

// Shared notification surface for every page using the application shell.
window.notify = (message, type = 'success') => {
  let stack = document.querySelector('.toast-stack');
  if (!stack) {
    stack = document.createElement('div');
    stack.className = 'toast-stack';
    stack.setAttribute('aria-label', 'Notificações');
    document.body.append(stack);
  }
  const card = document.createElement('div');
  card.className = `toast toast-${type}`;
  card.setAttribute('role', type === 'error' ? 'alert' : 'status');
  const text = document.createElement('span');
  text.textContent = message;
  const close = document.createElement('button');
  close.type = 'button'; close.textContent = '×'; close.setAttribute('aria-label', 'Fechar notificação');
  card.append(text, close); stack.append(card);
  let timer;
  const dismiss = () => { clearTimeout(timer); card.classList.add('toast-leaving'); setTimeout(() => card.remove(), 220); };
  const schedule = () => { clearTimeout(timer); timer = setTimeout(dismiss, 5000); };
  close.addEventListener('click', dismiss);
  card.addEventListener('mouseenter', () => clearTimeout(timer));
  card.addEventListener('mouseleave', schedule);
  card.addEventListener('focusin', () => clearTimeout(timer));
  card.addEventListener('focusout', schedule);
  schedule();
};
document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('[data-notification]').forEach(el => window.notify(el.textContent, el.dataset.notification));
});
