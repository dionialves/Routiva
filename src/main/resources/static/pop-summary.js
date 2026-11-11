'use strict';

// Apply the map threshold before calculating counts, event totals and worst severity.
function summarizePop(hosts, minimumSeverity = 0) {
  const unique = [...new Map(hosts.map(host => [host.id, host])).values()];
  const events = new Map();
  let affected = 0, operational = 0, disabled = 0, unknown = 0;
  for (const host of unique) {
    const incidents = (host.incidents || []).filter(issue => issue.severity >= minimumSeverity);
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
  use.setAttribute('href', `/icons.svg?v=20261110-4#${name}`);
  icon.append(use);
  return icon;
}

class PopSummary {
  constructor({stage, hosts, groupName, freshness, colors, labels, minimumSeverity = () => 0}) {
    Object.assign(this, {stage, hosts, groupName, freshness, colors, labels, minimumSeverity});
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
    element.onpointerenter = () => this.scheduleOpen(element, node);
    element.onpointerleave = () => this.scheduleClose();
    element.onfocus = () => this.scheduleOpen(element, node);
    element.onblur = () => this.scheduleClose();
    element.addEventListener('pointerdown', () => {
      if (this.node?.id !== node.id) this.close();
    });
  }
  scheduleOpen(element, node) {
    clearTimeout(this.openTimer);
    clearTimeout(this.closeTimer);
    if (this.node?.id === node.id) return;
    this.close();
    this.openTimer = setTimeout(() => {
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
        this.frame = requestAnimationFrame(track);
      };
      track();
    }, 250);
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
    const summary = summarizePop(this.hosts(this.node), this.minimumSeverity?.() ?? 0);
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
    name.append(el('strong', this.node.name), el('small', `Grupo Zabbix: ${this.groupName(this.node.groupId)}`));
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
        const copy = el('div', undefined, 'pop-incident-copy');
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
    const details = el('a', 'Ver detalhes do POP ↗', 'pop-details');
    details.setAttribute('href', `/diagnostico/grupos/${encodeURIComponent(this.node.groupId)}`);
    details.setAttribute('target', '_blank');
    details.setAttribute('rel', 'noopener noreferrer');
    details.setAttribute('aria-label', `Ver detalhes do POP ${this.node.name} (abre em nova aba)`);
    footer.append(updated, details);
    this.box.append(footer);
    this.updateFooter();
  }
  updateFooter() {
    const freshness = this.box.querySelector('.pop-freshness');
    if (!freshness) return;
    const fresh = this.freshness();
    const age = Math.max(0, Math.floor((Date.now() - fresh.updatedAt) / 1000));
    const text = fresh.failed || age > 90
      ? `Dados desatualizados · última atualização ${new Date(fresh.updatedAt).toLocaleTimeString('pt-BR')}`
      : `Atualizado há ${age} segundos`;
    if (freshness.textContent !== text) freshness.textContent = text;
  }
}
