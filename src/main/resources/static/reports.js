for (const card of document.querySelectorAll('[data-report-id]')) {
  card.addEventListener('click', () => {
    document.getElementById(card.getAttribute('aria-controls')).showModal();
  });
}

const backupSummaryDialog = document.getElementById('backup-summary-dialog');
for (const id of ['backup-summary-close', 'backup-summary-cancel']) {
  document.getElementById(id)?.addEventListener('click', () => backupSummaryDialog.close());
}

const backupDownload = document.getElementById('backup-summary-download');
backupDownload?.addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = backupDownload.querySelector('button');
  if (button.disabled) return;

  const error = document.getElementById('backup-summary-download-error');
  error.textContent = '';
  button.disabled = true;
  button.classList.add('system-button-loading');
  button.setAttribute('aria-busy', 'true');
  try {
    const response = await fetch(backupDownload.action);
    if (!response.ok || !response.headers.get('Content-Type')?.startsWith('application/pdf')) {
      throw new Error('PDF indisponível');
    }
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement('a');
    link.href = url;
    const disposition = response.headers.get('Content-Disposition');
    link.download = disposition?.match(/filename="([^"]+)"/i)?.[1] ?? 'resumo-backup.pdf';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  } catch {
    error.textContent = 'Não foi possível baixar o relatório. Tente novamente.';
  } finally {
    button.disabled = false;
    button.classList.remove('system-button-loading');
    button.removeAttribute('aria-busy');
  }
});
