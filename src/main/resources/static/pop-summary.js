'use strict';

// Apply the map threshold before calculating counts, event totals and worst severity.
function summarizePop(hosts, minimumSeverity = 0, showAcknowledged = true) {
  const unique = [...new Map(hosts.map(host => [host.id, host])).values()];
  const events = new Map();
  let affected = 0, operational = 0, disabled = 0, unknown = 0;
  for (const host of unique) {
    const incidents = (host.incidents || []).filter(issue => issue.severity >= minimumSeverity && (showAcknowledged || !issue.acknowledged));
    if (incidents.length) affected++;
    if (host.monitoring === 'disabled') disabled++;
    else if (host.monitoring !== 'monitored' || !host.incidents) unknown++;
    else if (!incidents.length) operational++;
    for (const issue of incidents) {
      const key = issue.eventId || `${host.id}:${issue.name}:${issue.openedAt}`;
      if (!events.has(key)) events.set(key, {...issue, hosts: new Set()});
      events.get(key).hosts.add(host.name);
    }
  }
  return {total: unique.length, affected, operational, disabled, unknown,
    incidents: [...events.values()].sort((a, b) => b.severity - a.severity || a.openedAt - b.openedAt)};
}

function popElapsed(timestamp, now = Date.now()) {
  if (!timestamp) return 'Horário indisponível';
  const minutes = Math.max(0, Math.floor((now - timestamp) / 60000));
  if (!minutes) return 'agora';
  if (minutes < 60) return `${minutes} min`;
  return `${Math.floor(minutes / 60)}h ${minutes % 60}min`;
}

// Reuse the application's shared outline sprite; no new icon dependency.
function popIcon(name, size = 16) {
  const namespace = 'http://www.w3.org/2000/svg';
  const icon = document.createElementNS(namespace, 'svg');
  for (const [key, value] of Object.entries({width: size, height: size, viewBox: '0 0 24 24',
    fill: 'none', stroke: 'currentColor', 'stroke-width': 1.8,
    'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true'})) {
    icon.setAttribute(key, value);
  }
  const use = document.createElementNS(namespace, 'use');
  use.setAttribute('href', `/icons.svg?v=20261120-host-summary#${name}`);
  icon.append(use);
  return icon;
}

class PopSummary {
  constructor({stage, hosts, metric = () => null, groupName, freshness, colors, labels, minimumSeverity = () => 0, showAcknowledged = () => true, menuOpen = () => false}) {
    Object.assign(this, {stage, hosts, metric, groupName, freshness, colors, labels, minimumSeverity, showAcknowledged, menuOpen});
    this.box = document.createElement('section');
    this.box.className = 'pop-summary';
    this.box.id = 'map-pop-summary';
    this.box.popover = 'manual';
    this.box.setAttribute('aria-label', 'Resumo do POP');
    this.box.onpointerenter = () => clearTimeout(this.closeTimer);
    this.box.onpointerleave = () => this.scheduleClose();
    this.box.onfocusin = () => clearTimeout(this.closeTimer);
    this.box.onfocusout = () => this.scheduleClose();
    this.stage.append(this.box);
    document.addEventListener('keydown', event => { if (event.key === 'Escape') this.close(); });
  }
  bind(element, node) {
    element.dataset.popId = node.id;
    element.setAttribute('aria-details', this.box.id);
    if (this.node?.id === node.id) this.anchor = element;
    element.setAttribute('tabindex', '0');
    let activated = false;
    const suppress = () => {
      activated = true;
      this.close();
    };
    element.onpointerenter = () => {
      if (this.menuOpen?.()) return;
      activated = false;
      this.scheduleOpen(element, node);
    };
    element.onpointerleave = () => this.scheduleClose();
    element.onfocus = () => {
      if (!activated && element.matches(':focus-visible')) this.scheduleOpen(element, node, 0);
    };
    element.onblur = () => this.scheduleClose();
    element.addEventListener('pointerdown', suppress);
    element.addEventListener('click', suppress, true);
    element.addEventListener('keydown', event => {
      if (event.key === 'Tab') activated = false;
    });
  }
  scheduleOpen(element, node, delay = 1000) {
    clearTimeout(this.openTimer);
    clearTimeout(this.closeTimer);
    const menuOpen = () => this.menuOpen?.() || element.getAttribute?.('aria-expanded') === 'true';
    if (this.node?.id === node.id || menuOpen()) return;
    this.close();
    this.openTimer = setTimeout(() => {
      if (menuOpen() || element.isConnected === false) return;
      this.node = node;
      this.anchor = element;
      this.expanded = false;
      this.render();
      this.box.showPopover();
      const track = () => {
        if (!this.node) return;
        // bind() updates the anchor whenever the map is redrawn.
        const anchor = this.anchor;
        if (!anchor || anchor.isConnected === false || this.stage.hidden) { this.close(); return; }
        this.position(anchor.getBoundingClientRect());
        this.updateFooter();
        if (this.node.hostId && !this.box.contains(document.activeElement) && !this.hostCache?.get(this.node.hostId)?.pending && Date.now() - (this.hostCache?.get(this.node.hostId)?.at || 0) >= 15000)
          this.renderHost();
        this.frame = requestAnimationFrame(track);
      };
      track();
    }, delay);
  }
  scheduleClose() {
    clearTimeout(this.openTimer);
    clearTimeout(this.closeTimer);
    this.closeTimer = setTimeout(() => {
      if (!this.box.matches(':hover') && !this.box.contains(document.activeElement)) this.close();
    }, 200);
  }
  close() {
    clearTimeout(this.openTimer);
    clearTimeout(this.closeTimer);
    cancelAnimationFrame(this.frame);
    this.node = null;
    this.box.hidePopover();
  }
  position(anchor) {
    const stage = this.stage.getBoundingClientRect();
    const left = Math.max(8, stage.left), top = Math.max(8, stage.top);
    const right = Math.min(window.innerWidth - 8, stage.right);
    const bottom = Math.min(window.innerHeight - 8, stage.bottom);
    if (anchor.right < left || anchor.left > right || anchor.bottom < top || anchor.top > bottom) {
      this.box.style.visibility = 'hidden';
      return;
    }
    this.box.style.visibility = '';
    this.box.style.maxWidth = `${Math.max(0, right - left)}px`;
    this.box.style.maxHeight = `${Math.max(0, bottom - top)}px`;
    const rect = this.box.getBoundingClientRect();
    let x = anchor.right + 14, y = anchor.top;
    if (x + rect.width > right) x = anchor.left - rect.width - 14;
    if (y + rect.height > bottom) y = anchor.bottom - rect.height;
    this.box.style.left = `${Math.max(left, Math.min(x, right - rect.width))}px`;
    this.box.style.top = `${Math.max(top, Math.min(y, bottom - rect.height))}px`;
  }
  render() {
    if (!this.node) return;
    this.box.classList.toggle('pop-host-summary', !!this.node.hostId);
    if (this.node.hostId) { this.renderHost(); return; }
    this.box.setAttribute('aria-label', 'Resumo do POP');
    const metric = this.metric?.(this.node);
    this.monitoringUpdatedAt = undefined;
    if (metric) { this.renderMetric(metric); return; }
    const summary = summarizePop(this.hosts(this.node), this.minimumSeverity?.() ?? 0, this.showAcknowledged?.() ?? true);
    const fresh = this.freshness();
    const el = (tag, text, className) => {
      const item = document.createElement(tag);
      if (text !== undefined) item.textContent = text;
      if (className) item.className = className;
      return item;
    };
    const severity = summary.incidents[0]?.severity;
    const statusColor = severity === undefined ? 'var(--muted)' : this.colors[severity + 2];
    this.box.style.setProperty('--pop-severity', statusColor);
    const heading = el('header');
    const name = el('div', undefined, 'pop-heading-copy');
    name.append(el('strong', this.node.name), el('small', this.node.hostId ? 'Host Zabbix' : `Grupo Zabbix: ${this.groupName(this.node.groupId)}`));
    const symbol = el('span', undefined, 'pop-symbol');
    symbol.append(popIcon('pop-router', 18));
    heading.append(symbol, name);
    if (fresh.updatedAt) heading.append(el('span', `${summary.incidents.length} ${summary.incidents.length === 1 ? 'incidente' : 'incidentes'}`, 'pop-badge'));
    this.box.replaceChildren(heading);
    const failure = () => {
      const message = el('div', undefined, 'pop-message pop-failure');
      message.setAttribute('role', 'status');
      message.append(popIcon('wifi-off'), el('span', 'Não foi possível atualizar os dados deste POP.'));
      return message;
    };
    if (!fresh.updatedAt) {
      if (fresh.failed) this.box.append(failure());
      else {
        const loading = el('div', undefined, 'pop-loading');
        loading.setAttribute('role', 'status');
        loading.append(el('span', undefined, 'pop-spinner'), el('span', 'Carregando resumo…'));
        this.box.append(loading);
      }
      return;
    }
    const metrics = el('div', undefined, 'pop-metrics');
    for (const [label, value, state] of [
      ['Equipamentos', summary.total, 'neutral'],
      ['Sem incidentes', summary.operational, 'operational'],
      ['Afetados', summary.affected, 'affected']
    ]) {
      const metric = el('div', undefined, `pop-metric pop-${state}`);
      const valueLine = el('div', undefined, 'pop-metric-value');
      valueLine.append(el('strong', value));
      metric.append(valueLine, el('small', label));
      metrics.append(metric);
    }
    this.box.append(metrics);
    if (summary.disabled || summary.unknown) {
      const note = el('div', undefined, 'pop-note');
      note.append(el('small',
        `${summary.disabled} desabilitados · ${summary.unknown} sem monitoramento confirmado`));
      this.box.append(note);
    }
    if (fresh.failed) this.box.append(failure());
    if (!summary.total) this.box.append(el('p', 'Nenhum equipamento visível neste grupo.', 'pop-no-hosts'));
    this.box.append(el('h3', 'Incidentes ativos'));
    if (!summary.incidents.length) {
      const empty = el('div', undefined, 'pop-message pop-empty');
      const copy = el('div');
      copy.append(el('strong', 'Nenhum incidente ativo'),
        el('small', 'Nenhum problema ativo identificado no Zabbix.'));
      empty.append(popIcon('check-circle', 18), copy);
      this.box.append(empty);
    } else {
      const list = el('ul', undefined, 'pop-incidents');
      list.setAttribute('aria-label', 'Incidentes ativos');
      list.setAttribute('tabindex', '0');
      list.classList.toggle('pop-expanded', !!this.expanded);
      for (const issue of summary.incidents.slice(0, this.expanded ? undefined : 5)) {
        const item = el('li');
        const alert = el('span', undefined, 'pop-severity');
        alert.style.color = this.colors[issue.severity + 2];
        alert.title = this.labels[issue.severity + 2];
        alert.setAttribute('role', 'img');
        alert.setAttribute('aria-label', alert.title);
        const copy = el(issue.eventId ? 'a' : 'div', undefined, 'pop-incident-copy');
        if (issue.eventId) {
          copy.setAttribute('href', `/incidentes?eventId=${encodeURIComponent(issue.eventId)}`);
          copy.setAttribute('target', '_blank');
          copy.setAttribute('rel', 'noopener noreferrer');
          copy.setAttribute('aria-label', `${issue.name} — ${[...issue.hosts].join(', ')} (abre em nova aba)`);
        }
        const row = el('div', undefined, 'pop-incident-heading');
        const duration = el('time');
        if (issue.openedAt) duration.setAttribute('datetime', new Date(issue.openedAt).toISOString());
        duration.append(el('span', popElapsed(issue.openedAt)));
        row.append(el('strong', [...issue.hosts].join(', ')), duration);
        copy.append(row, el('small', issue.name));
        item.append(alert, copy);
        list.append(item);
      }
      this.box.append(list);
      if (!this.expanded && summary.incidents.length > 5) {
        const more = el('button', undefined, 'pop-more');
        more.append(el('span', `Ver mais ${summary.incidents.length - 5} ${summary.incidents.length === 6 ? 'incidente' : 'incidentes'}`), popIcon('chevron-down', 14));
        more.type = 'button';
        more.setAttribute('aria-expanded', 'false');
        more.onclick = () => {
          this.expanded = true;
          this.render();
          this.box.querySelector('.pop-incidents').focus({preventScroll: true});
        };
        this.box.append(more);
      }
    }
    const footer = el('footer');
    const refresh = popIcon('refresh', 12);
    refresh.setAttribute('stroke-width', '1.5');
    const updated = el('span', undefined, 'pop-updated');
    updated.append(refresh, el('span', undefined, 'pop-freshness'));
    footer.append(updated);
    this.box.append(footer);
    this.updateFooter();
  }
  renderHost() {
    const id = this.node.hostId;
    this.hostCache ??= new Map();
    const cached = this.hostCache.get(id);
    const data = cached?.data;
    this.box.setAttribute('aria-label', 'Resumo do host');
    const el = (tag, text, className) => {
      const item = document.createElement(tag);
      if (text != null) item.textContent = text;
      if (className) item.className = className;
      return item;
    };
    const heading = el('header');
    const symbol = el('span', null, 'pop-symbol'); symbol.append(popIcon('server', 18));
    const name = el('div', null, 'pop-heading-copy');
    name.append(el('strong', data?.name || this.node.name));
    if (data?.description) name.append(el('small', data.description));
    const states = {online:'Disponível', offline:'Indisponível', unknown:'Desconhecido'};
    const activeCount = el('small', data?.active == null ? '— incidentes ativos' :
      `${data.active} ${data.active === 1 ? 'incidente ativo' : 'incidentes ativos'}`, 'host-active-count');
    heading.append(symbol, name, el('span', states[data?.status] || states.unknown,
      'host-state ' + (data?.status || 'unknown')), activeCount);
    if (data?.active > 0) {
      const severity = data.activeSeverity;
      activeCount.style.color = this.colors[severity + 2] || 'var(--warning)';
    }
    const restoreHistoryFocus = this.box.querySelector('.host-all-incidents') === document.activeElement;
    this.box.replaceChildren(heading);
    this.box.setAttribute('aria-busy', String(!data && !cached?.error));
    const metrics = el('div', null, 'pop-metrics host-summary-metrics');
    const format = (v, digits, suffix) => Number.isFinite(v)
      ? v.toLocaleString('pt-BR', {minimumFractionDigits:digits, maximumFractionDigits:digits}) + suffix : '—';
    const values = data?.metrics;
    for (const [title, icon, value, digits, suffix, note, state] of [
      ['Disponibilidade Operacional', 'pulse', values?.operational, 2, '%', 'Últimas 24 h', 'operational'],
      ['Latência média', 'timer', values?.latency, 1, ' ms',
        Number.isFinite(data?.p95) ? 'P95: ' + format(data.p95, 1, ' ms') : 'P95: —', data?.latencyState],
      ['Perda de pacotes', 'arrow-down-up', values?.loss, 2, '%', 'Últimas 24 h', data?.lossState]
    ]) {
      const item = el('div', null, 'pop-metric');
      item.append(popIcon(icon, 17), el('span', title, 'host-metric-label'),
        el('strong', format(value, digits, suffix), 'host-metric-value ' + (Number.isFinite(value) ? state : '')),
        el('small', Number.isFinite(value) ? note : 'Sem dados'));
      metrics.append(item);
    }
    this.box.append(metrics);
    const title = el('h3'); title.append(popIcon('alert'), document.createTextNode('Últimos 5 incidentes'));
    this.box.append(title);
    {
      const table = document.getElementById('map-host-incidents-table').content.cloneNode(true);
      const body = table.querySelector('tbody');
      for (const issue of (data?.incidents ?? []).slice(0, 5)) {
        const row = el('tr');
        const date = el('td', hostIncidentDate(issue.timestamp));
        date.title = new Date(issue.timestamp).toLocaleString('pt-BR');
        const severity = el('td');
        const badge = el('span', this.labels[issue.severity + 2] || 'Desconhecida', 'host-severity');
        badge.style.color = this.colors[issue.severity + 2] || 'var(--muted)';
        severity.append(badge);
        const text = el('td', issue.name, 'host-incident-name'); text.title = issue.name;
        const status = el('td'), state = el('span', null, 'host-incident-status ' + (issue.resolved ? 'resolved' : 'active'));
        const statusLabel = issue.resolved ? 'Resolvido' : 'Aberto';
        state.setAttribute('role', 'img');
        state.setAttribute('aria-label', statusLabel);
        state.title = statusLabel;
        state.append(popIcon(issue.resolved ? 'check-circle' : 'circle-alert', 16));
        status.append(state); row.append(date, severity, text, status); body.append(row);
      }
      const pager = table.querySelector('.system-pagination');
      this.box.append(table);
      TablePagination.render(pager, 1, 5, body.children.length);
      if (cached?.error || data?.warning) globalThis.TableState?.error(body, data?.warning);
      else if (data) globalThis.TableState?.done(body);
      else globalThis.TableState?.loading(body);
    }
    const link = el('a', null, 'host-all-incidents');
    link.href = `/incidentes?hostId=${encodeURIComponent(id)}&history=all`;
    link.append(popIcon('list', 15), document.createTextNode('Ver todos os incidentes'), popIcon('arrow-right', 14));
    this.box.append(link);
    if (restoreHistoryFocus) link.focus({preventScroll:true});
    const footer = el('footer'), updated = el('span', null, 'pop-updated');
    updated.append(popIcon('refresh', 12), el('span', null, 'pop-freshness'));
    footer.append(updated); this.box.append(footer);
    this.monitoringUpdatedAt = data?.updatedAt ?? null;
    this.updateFooter();
    if (cached?.pending || (cached && Date.now() - cached.at < 15000)) return;
    const entry = {at:Date.now(), data, pending:true};
    // Bound memory when browsing many maps; in-flight entries remain shared per host.
    for (const [key, item] of this.hostCache) if (!item.pending && Date.now() - item.at > 60000) this.hostCache.delete(key);
    this.hostCache.set(id, entry);
    fetch(`/api/maps/hosts/${encodeURIComponent(id)}/summary`, {cache:'no-store', signal:AbortSignal.timeout(20000)})
      .then(response => { if (!response.ok) throw new Error('Resumo indisponível'); return response.json(); })
      .then(result => { entry.data = result; entry.error = false; })
      .catch(() => { entry.error = true; })
      .finally(() => {
        entry.pending = false; entry.at = Date.now();
        if (this.node?.hostId === id) this.renderHost();
      });
  }
  renderMetric(metric) {
    if (metric.view === 'latency' || metric.view === 'loss') {
      this.renderMonitoring(metric);
      return;
    }
    const el = (tag, text, className) => {
      const item = document.createElement(tag);
      item.textContent = text;
      if (className) item.className = className;
      return item;
    };
    const heading = el('header', this.node.name);
    const value = el('h3', `${metric.label}: ${metric.formatted}`);
    const state = el('p', metric.state);
    state.style.color = metric.color;
    const fresh = this.freshness();
    this.box.replaceChildren(heading, value, state, el('p', metric.period), el('small', metric.range));
    if (!fresh.updatedAt) this.box.append(el('p', fresh.failed ? 'Indicadores indisponíveis.' : 'Carregando indicadores…'));
    else {
      const footer = el('footer', '');
      footer.append(el('span', '', 'pop-freshness'));
      this.box.append(footer);
      this.updateFooter();
    }
  }
  renderMonitoring(metric) {
    const loss = metric.view === 'loss';
    const summary = metric.current;
    const el = (tag, text, className) => {
      const item = document.createElement(tag);
      if (text !== undefined) item.textContent = text;
      if (className) item.className = className;
      return item;
    };
    const colors = {NORMAL:'var(--success)', ATENCAO:'var(--warning, #fced4e)',
      CRITICO:'#ff0000'};
    const color = state => colors[state] || 'var(--muted)';
    const format = value => value == null ? 'Sem dados' :
      value.toLocaleString('pt-BR', {maximumFractionDigits:loss ? 1 : 2}) + (loss ? '%' : ' ms');
    this.box.style.setProperty('--pop-severity', 'var(--warning, #fced4e)');
    const heading = el('header');
    const symbol = el('span', undefined, 'pop-symbol');
    symbol.append(popIcon(loss ? 'packet-loss' : 'pulse'));
    const name = el('div', undefined, 'pop-heading-copy');
    name.append(el('strong', this.node.name), el('small', this.node.hostId ? 'Host Zabbix' :
      `Grupo Zabbix: ${this.groupName(this.node.groupId)}`));
    heading.append(symbol, name);
    if (summary) heading.append(el('span', `${summary.aboveLimit} em alerta`, 'pop-badge'));
    this.box.replaceChildren(heading);
    const metrics = el('div', undefined, 'pop-metrics');
    for (const [label, value, state] of [
      [loss ? 'Perda média' : 'Latência média', format(summary?.average), null],
      [loss ? 'Maior perda' : 'Maior latência', format(summary?.maximum), summary?.state],
      ['Acima do limite', summary?.maximum == null ? 'Sem dados' : summary.aboveLimit,
        summary?.aboveLimit ? summary.state : null]
    ]) {
      const item = el('div', undefined, 'pop-metric');
      const line = el('div', undefined, 'pop-metric-value');
      line.style.color = state ? color(state) : 'var(--text)';
      line.append(el('strong', value));
      item.append(line, el('small', label));
      metrics.append(item);
    }
    this.box.append(metrics);
    if (loss) this.box.append(el('small', 'Perda consolidada por pacotes · ' + metric.period, 'pop-note'));
    if (this.freshness().failed) this.box.append(el('p', 'Não foi possível atualizar os dados deste POP.', 'pop-message'));
    this.box.append(el('h3', loss ? 'Maiores perdas' : 'Maiores latências'));
    const list = el('ul', undefined, 'pop-incidents');
    for (const host of summary?.hosts || []) {
      const item = el('li');
      const bar = el('span', undefined, 'pop-severity');
      bar.style.color = color(host.state);
      bar.setAttribute('aria-label', host.state === 'CRITICO' ? 'Crítico' : host.state === 'ATENCAO' ? 'Atenção' : host.state === 'NORMAL' ? 'Normal' : 'Sem dados');
      bar.setAttribute('role', 'img');
      const copy = el('div', undefined, 'pop-incident-copy');
      const row = el('div', undefined, 'pop-incident-heading');
      row.append(el('strong', host.name));
      copy.append(row, el('small', loss ? 'Perda de pacotes' : 'Latência atual'));
      item.append(bar, copy, el('span', format(host.latency), 'pop-reading'));
      list.append(item);
    }
    this.box.append(list);
    if (!summary?.hosts?.length) this.box.append(el('p', 'Sem dados', 'pop-no-hosts'));
    const footer = el('footer');
    const updated = el('span', undefined, 'pop-updated');
    updated.append(popIcon('refresh', 12), el('span', undefined, 'pop-freshness'));
    footer.append(updated);
    if (this.node.hostId) {
      const details = el('a', 'Ver detalhes do host ↗', 'pop-details');
      details.setAttribute('href', metric.detailsUrl);
      details.setAttribute('target', '_blank');
      details.setAttribute('rel', 'noopener noreferrer');
      footer.append(details);
    }
    this.box.append(footer);
    this.monitoringUpdatedAt = summary?.updatedAt ?? null;
    this.updateFooter();
  }
  updateFooter() {
    const freshness = this.box.querySelector('.pop-freshness');
    if (!freshness) return;
    const fresh = this.freshness();
    const updatedAt = this.monitoringUpdatedAt === undefined ? fresh.updatedAt : this.monitoringUpdatedAt;
    if (!updatedAt) { freshness.textContent = 'Horário indisponível'; return; }
    const age = Math.max(0, Math.floor((Date.now() - updatedAt) / 1000));
    const failed = this.node?.hostId ? this.hostCache?.get(this.node.hostId)?.error ||
      this.hostCache?.get(this.node.hostId)?.data?.warning : fresh.failed;
    const text = failed || age > 90
      ? `Dados desatualizados · última atualização ${new Date(updatedAt).toLocaleTimeString('pt-BR')}`
      : `Atualizado há ${age} segundos`;
    if (freshness.textContent !== text) freshness.textContent = text;
  }
}

function hostIncidentDate(timestamp, now = Date.now()) {
  if (!timestamp) return '—';
  const date = new Date(timestamp), today = new Date(now), yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  const day = date.toDateString() === today.toDateString() ? 'Hoje' :
    date.toDateString() === yesterday.toDateString() ? 'Ontem' : date.toLocaleDateString('pt-BR');
  return `${day}, ${date.toLocaleTimeString('pt-BR', {hour:'2-digit', minute:'2-digit'})}`;
}
if (typeof module !== 'undefined') module.exports = {summarizePop, popElapsed, hostIncidentDate};
