(() => {
  const page = document.querySelector('.latency-summary');
  if (!page) return;
  const state = document.getElementById('latency-summary-state');
  const text = (id, value) => { document.getElementById(id).textContent = value; };
  const format = (value, unit = ' ms') => value === null ? '—' : value.toLocaleString('pt-BR', { maximumFractionDigits: 2 }) + unit;
  const url = '/api/ap/' + encodeURIComponent(page.dataset.hostId) + '/latency/' + encodeURIComponent(page.dataset.assessmentId);
  async function load() {
    globalThis.TableState?.loading('summary-results');
    try {
      const response = await fetch(url, { cache: 'no-store' });
      const view = await response.json();
      if (!response.ok) throw new Error(view.message || 'Resultados indisponíveis.');
      if (view.failed) throw new Error('A avaliação não concluiu todos os pacotes.');
      if (!view.completed) {
        state.textContent = 'Avaliação em andamento. Aguardando a conclusão dos pacotes selecionados…';
        setTimeout(load, 1000);
        return;
      }
      const summary = window.ApLatency.summarize(view.results);
      text('summary-ap', format(summary.ap.mean));
      text('summary-ap-loss', 'Perda do AP: ' + format(summary.ap.loss, '%'));
      text('summary-mean', format(summary.mean));
      text('summary-delta', format(summary.delta));
      text('summary-loss', format(summary.loss, '%'));
      text('summary-coverage', `${summary.clients.length} clientes conectados · ${summary.measured} com resposta · ${summary.noReply} sem resposta · ${summary.unavailable} sem medição válida.`);
      const warnings = [];
      if (summary.ap.mean === null) warnings.push('O AP não respondeu: a comparação de latência está indisponível.');
      if (summary.clients.some(client => client.delta !== null && client.delta < 0)) warnings.push('Há diferenças negativas, que indicam variação da medição.');
      if (summary.noReply || summary.unavailable) warnings.push('A média geral não inclui clientes sem resposta ou sem medição válida.');
      text('summary-alert', warnings.join(' '));
      text('latency-summary-period', `${new Date(view.startedAt).toLocaleString('pt-BR')} até ${new Date(view.endsAt).toLocaleTimeString('pt-BR')} · ${view.packetCount} pacotes por destino`);
      const worst = document.getElementById('summary-worst');
      worst.replaceChildren();
      for (const client of summary.worst) {
        const item = document.createElement('li');
        const name = document.createElement('strong');
        name.textContent = client.target.name;
        const details = document.createElement('p');
        details.textContent = `${client.target.ip} · ${client.target.mac} · Média: ${format(client.stats.mean)} · Diferença para o AP: ${format(client.delta)} · Perda: ${format(client.stats.loss, '%')}`;
        item.append(name, details);
        worst.append(item);
      }
      document.getElementById('summary-worst-empty').hidden = summary.worst.length > 0;
      const body = document.getElementById('summary-results');
      body.replaceChildren();
      const rows = [{ ...view.results[0], stats: summary.ap, delta: null }, ...summary.clients];
      rows.forEach((result, index) => {
        const stats = result.stats;
        const warning = result.target.warning || (stats.total === 0 ? 'Sem amostras do coletor'
          : stats.loss === null ? 'Falha na coleta' : stats.replies === 0 ? 'Sem resposta'
            : result.delta !== null && result.delta < 0 ? 'Variação da medição' : '');
        const row = document.createElement('tr');
        for (const value of [result.target.name, [result.target.ip, result.target.mac].filter(Boolean).join(' / ') || '—',
          format(stats.mean), format(stats.min), format(stats.max), format(stats.loss, '%'),
          `${stats.replies}/${stats.total}`, index ? format(result.delta) : 'Referência (AP)', warning]) {
          const cell = document.createElement('td');
          cell.textContent = value;
          row.append(cell);
        }
        body.append(row);
      });
      document.getElementById('latency-summary-content').hidden = false;
      globalThis.TableState?.done('summary-results');
      state.textContent = 'Avaliação concluída.';
    } catch (error) { globalThis.TableState?.error('summary-results', error.message); state.textContent = error.message + ' Volte ao AP para iniciar uma nova avaliação.'; }
  }
  load();
})();
