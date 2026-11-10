/* The stored execution is independent of live inventory queries and local selection edits. */
class ZabbixImportLog {
  constructor() {
    this.result = null;
    this.entries = [];
    this.filter = 'all';
    this.query = '';
    this.severity = 'all';
    this.page = 0;
    this.pageSize = 25;
    this.descending = false;
  }
  static normalize(value) {
    return String(value ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  }
  load(result, knownHosts = []) {
    this.result = result?.state ? result : null;
    const hosts = new Map(knownHosts.map(host => [host.id, host]));
    // Older executions stored only errors. Never manufacture successful per-host records.
    this.entries = (result?.entries || (result?.errors || []).map(error => {
      const missing = error.reason?.startsWith('Ausente da origem');
      const host = hosts.get(error.id);
      return { id: error.id, name: host?.name || '', ip: host?.ip || '',
        outcome: missing ? 'missing' : 'failed', category: missing ? 'Origem' :
          error.reason?.includes('IPv4') ? 'Erro de validação' : 'Importação',
        severity: missing ? 'warning' : 'error', message: error.reason, timestamp: result.finishedAt };
    })).map(entry => ({ ...entry, id: String(entry.id),
      search: ZabbixImportLog.normalize(`${entry.id} ${entry.name || ''} ${entry.ip || ''} ${entry.category} ${entry.message}`) }));
    this.page = 0;
  }
  matches(entry, filter = this.filter) {
    return filter === 'all' || (filter === 'ignored' ? ['ignored', 'missing'].includes(entry.outcome) : entry.outcome === filter);
  }
  count(filter) {
    const aggregate = { failed: 'failed', ignored: 'ignored', imported: 'imported', updated: 'updated' }[filter];
    if (aggregate && typeof this.result?.[aggregate] === 'number') return this.result[aggregate];
    return this.entries.filter(entry => this.matches(entry, filter)).length;
  }
  filtered() {
    const query = ZabbixImportLog.normalize(this.query.trim());
    return this.entries.filter(entry => this.matches(entry)
      && (this.severity === 'all' || entry.severity === this.severity) && entry.search.includes(query))
      .sort((a, b) => (a.name || a.id).localeCompare(b.name || b.id, 'pt-BR', { numeric: true }) * (this.descending ? -1 : 1));
  }
  visible() {
    const entries = this.filtered();
    const pages = Math.max(1, Math.ceil(entries.length / this.pageSize));
    this.page = Math.max(0, Math.min(this.page, pages - 1));
    return { entries: entries.slice(this.page * this.pageSize, (this.page + 1) * this.pageSize), total: entries.length, pages };
  }
}

class ZabbixImportResults {
  constructor(document, icon) {
    this.document = document;
    this.icon = icon;
    this.model = new ZabbixImportLog();
    this.$ = id => document.getElementById(id);
    this.$('import-search').addEventListener('input', () => {
      this.model.query = this.$('import-search').value; this.model.page = 0; this.renderTable();
    });
    this.$('import-severity').addEventListener('change', () => {
      this.model.severity = this.$('import-severity').value; this.model.page = 0; this.renderTable();
    });
    this.$('import-sort').addEventListener('click', () => {
      this.model.descending = !this.model.descending; this.model.page = 0; this.renderTable();
    });
    this.$('import-previous').addEventListener('click', () => { this.model.page--; this.renderTable(); });
    this.$('import-next').addEventListener('click', () => { this.model.page++; this.renderTable(); });
    this.$('import-details').addEventListener('click', event => {
      if (event.target === this.$('import-details')) {
        const bounds = event.target.getBoundingClientRect();
        if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) event.target.close();
      }
    });
  }
  node(tag, text, className = '') {
    const element = this.document.createElement(tag);
    element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  }
  load(data, hosts) {
    this.model.load(data, hosts);
    this.$('import-result').hidden = !this.model.result;
    if (!this.model.result) return;
    const states = { completed: 'Importação concluída', partial: 'Importação parcialmente concluída', failed: 'Falha na importação', running: 'Importação sem conclusão confirmada' };
    const time = data.finishedAt ? ` · Última importação: ${new Date(data.finishedAt).toLocaleString('pt-BR')}` : '';
    this.$('import-execution').textContent = `${states[data.state] || 'Resultado da execução'}${time}`;
    this.$('import-execution').dataset.state = data.state;
    const summary = this.$('import-summary'); summary.replaceChildren();
    const cards = [
      ['selectionSaved', 'Seleção salva', 'check', 'success'],
      ['imported', 'Importados', 'download', 'info'],
      ['updated', 'Atualizados', 'refresh', 'info'],
      ['unchanged', 'Sem alterações', 'equal', 'neutral'],
      ['ignored', 'Ignorados', 'alert', 'warning'],
      ['failed', 'Com erro', 'error', 'error']
    ];
    cards.forEach(([key, label, icon, status]) => {
      const card = this.node('div', undefined, `import-metric tone-${status}`);
      const mark = this.node('span', undefined, 'import-metric-icon'); mark.append(this.icon(icon));
      const content = this.node('div');
      content.append(this.node('span', label));
      if (key === 'selectionSaved') {
        content.append(this.node('small', data.selectionSaved === false ? 'Não confirmada' : 'Configuração registrada'));
      } else content.append(this.node('strong', Number.isFinite(data[key]) ? String(data[key]) : '—'));
      card.append(mark, content); summary.append(card);
    });
    this.renderFilters(); this.renderTable();
  }
  renderFilters() {
    const filters = this.$('import-filters'); filters.replaceChildren();
    [['all', 'Todos'], ['failed', 'Erros'], ['ignored', 'Ignorados'], ['missing', 'Ausentes da origem'], ['imported', 'Importados'], ['updated', 'Atualizados']].forEach(([value, label]) => {
      const button = this.node('button', `${label} (${this.model.count(value)})`, 'filter-pill');
      button.type = 'button'; button.setAttribute('aria-pressed', String(value === this.model.filter));
      button.addEventListener('click', () => { this.model.filter = value; this.model.page = 0; this.renderFilters(); this.renderTable(); });
      filters.append(button);
    });
  }
  renderTable() {
    const { entries, total, pages } = this.model.visible();
    const list = this.$('import-log'); list.replaceChildren();
    const statuses = { error: ['Erro', 'error'], warning: ['Aviso', 'alert'], success: ['Sucesso', 'check-circle'], info: ['Informação', 'info'] };
    entries.forEach(entry => {
      const tr = this.node('tr');
      const host = this.node('td');
      const button = this.node('button', entry.name || entry.id, 'result-host-button'); button.type = 'button';
      button.setAttribute('aria-label', `Detalhes de ${entry.name || entry.id}`);
      host.append(button);
      if (entry.name) host.append(this.node('small', `ID ${entry.id}${entry.ip ? ` · ${entry.ip}` : ''}`));
      const [status, icon] = statuses[entry.severity] || statuses.info;
      const category = this.node('td');
      const categoryLabel = this.node('span', undefined, `result-category tone-${entry.severity}`);
      categoryLabel.append(this.icon(icon), this.node('span', entry.category)); category.append(categoryLabel);
      const message = this.node('td', entry.message);
      const badge = this.node('td'); badge.append(this.node('span', status, `status-badge tone-${entry.severity}`));
      tr.append(host, category, message, badge);
      tr.addEventListener('click', () => this.details(entry));
      list.append(tr);
    });
    if (!entries.length) {
      const row = this.node('tr'), cell = this.node('td', this.model.result?.state === 'running'
        ? 'A execução anterior não teve conclusão confirmada. Salve e importe novamente para tentar concluir.'
        : 'Nenhum registro corresponde aos filtros. Execuções antigas podem conter apenas os detalhes de erros.');
      cell.colSpan = 4; cell.className = 'table-empty'; row.append(cell); list.append(row);
    }
    this.$('import-page').textContent = `${total} registros · Página ${this.model.page + 1} de ${pages}`;
    this.$('import-previous').disabled = this.model.page === 0;
    this.$('import-next').disabled = this.model.page + 1 >= pages;
    this.$('import-sort-heading').setAttribute('aria-sort', this.model.descending ? 'descending' : 'ascending');
  }
  details(entry) {
    const fields = this.$('import-detail-fields'); fields.replaceChildren();
    const labels = { id: 'ID do host', name: 'Nome', hostname: 'Hostname', ip: 'Endereço recebido', category: 'Categoria', message: 'Mensagem' };
    Object.entries(labels).forEach(([key, label]) => {
      fields.append(this.node('dt', label), this.node('dd', entry[key] || 'Não informado'));
    });
    if (entry.timestamp) fields.append(this.node('dt', 'Data e horário'), this.node('dd', new Date(entry.timestamp).toLocaleString('pt-BR')));
    this.$('import-details').showModal();
  }
}
if (typeof module !== 'undefined') module.exports = { ZabbixImportLog, ZabbixImportResults };
