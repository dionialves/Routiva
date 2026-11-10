const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const context = vm.createContext({});
vm.runInContext(fs.readFileSync('src/main/resources/static/pop-summary.js', 'utf8'), context);
const summarize = hosts => context.summarizePop(hosts);
const issue = (eventId, severity, openedAt) => ({eventId, severity, openedAt, name: 'Problem'});
const host = (id, incidents, monitoring = 'monitored') => ({id, name: id, incidents, monitoring});

test('counts distinct hosts and events independently, preserving unknown and disabled states', () => {
  const a = host('a', [issue('1', 5, 100), issue('2', 3, 200)]);
  const result = summarize([a, a, host('b', [issue('1', 5, 100)]), host('c', []),
    host('d', null, 'disabled'), host('e', [], 'unknown')]);
  assert.equal(result.total, 5);
  assert.equal(result.affected, 2);
  assert.equal(result.operational, 1);
  assert.equal(result.disabled, 1);
  assert.equal(result.unknown, 1);
  assert.equal(result.incidents.length, 2);
  assert.equal(result.incidents[0].hosts.size, 2);
});

test('sorts by worst severity then oldest event; does not truncate underlying data', () => {
  const result = summarize([host('a', [issue('a', 1, 100), issue('b', 5, 300), issue('c', 5, 200),
    issue('d', 0, 0), issue('e', 3, 100), issue('f', 4, 100)])]);
  assert.equal(result.incidents.map(i => i.eventId).join(','), 'c,b,f,e,a,d');
  assert.equal(result.incidents.length, 6);
});

test('empty and missing data do not imply operational hosts', () => {
  assert.equal(summarize([]).total, 0);
  assert.equal(summarize([host('a', null)]).operational, 0);
  assert.equal(context.popElapsed(1000, 5041000), '1h 24min');
  assert.equal(context.popElapsed(0), 'Horário indisponível');
});

test('position flips at viewport edges and retains fixed screen dimensions', () => {
  context.window = {innerWidth: 1000, innerHeight: 800};
  context.subject = {
    stage: {getBoundingClientRect: () => ({left: 100, top: 100, right: 950, bottom: 750})},
    box: {style: {}, getBoundingClientRect: () => ({width: 374, height: 400})}
  };
  vm.runInContext('PopSummary.prototype.position.call(subject, {left:900,right:924,top:650,bottom:674})', context);
  assert.equal(context.subject.box.style.left, '512px');
  assert.equal(context.subject.box.style.top, '274px');
  vm.runInContext('PopSummary.prototype.position.call(subject, {left:120,right:144,top:120,bottom:144})', context);
  assert.equal(context.subject.box.style.left, '158px');
  assert.equal(context.subject.box.style.top, '120px');
});

test('hover waits 250ms, allows transfer to the card and cancels quick passes', () => {
  const callbacks = new Map();
  let counter = 0;
  context.setTimeout = (fn, delay) => { callbacks.set(++counter, {fn, delay}); return counter; };
  context.clearTimeout = id => callbacks.delete(id);
  context.cancelAnimationFrame = () => {};
  context.requestAnimationFrame = () => 1;
  context.document = {activeElement: null};
  const anchor = {dataset: {popId: 'a'}, getBoundingClientRect: () => ({})};
  const box = {showPopover() { this.open = true; }, hidePopover() { this.open = false; },
    matches() { return this.hover; }, contains() { return false; }};
  context.hoverSubject = {box, stage: {querySelectorAll: () => [anchor]},
    render() {}, position() {}, updateFooter() {}};
  vm.runInContext('Object.setPrototypeOf(hoverSubject, PopSummary.prototype)', context);
  const subject = context.hoverSubject;
  subject.scheduleOpen(anchor, {id: 'a'});
  assert.equal(box.open, false);
  assert.equal(callbacks.get(subject.openTimer).delay, 250);
  subject.scheduleClose();
  assert.equal(callbacks.has(subject.openTimer), false);
  subject.scheduleOpen(anchor, {id: 'a'});
  callbacks.get(subject.openTimer).fn();
  assert.equal(box.open, true);
  subject.scheduleClose();
  box.hover = true;
  callbacks.get(subject.closeTimer).fn();
  assert.equal(box.open, true);
  box.hover = false;
  subject.scheduleClose();
  callbacks.get(subject.closeTimer).fn();
  assert.equal(box.open, false);
});

test('disabled and unconfirmed hosts with active problems overlap affected but never operational', () => {
  const result = summarize([host('a', [issue('a', 4, 100)], 'disabled'),
    host('b', [issue('b', 2, 100)], 'unknown'), host('c', [])]);
  assert.equal(result.total, 3);
  assert.equal(result.affected, 2);
  assert.equal(result.operational, 1);
  assert.equal(result.disabled, 1);
  assert.equal(result.unknown, 1);
});

// Exercise rendered states without a browser dependency, using the same DOM methods as the component.
class PopElement {
  constructor(tag) {
    this.tag = tag;
    this.children = [];
    this.attributes = {};
    this.style = {setProperty(key, value) { this[key] = value; }};
    this.classList = {toggle: (name, active) => {
      if (active) this.className = `${this.className || ''} ${name}`;
    }};
  }
  set textContent(value) { this.text = String(value); this.children = []; }
  get textContent() { return (this.text || '') + this.children.map(child => child.textContent).join(' '); }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.text = ''; this.children = children; }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  focus() { this.focused = true; }
  querySelector(selector) {
    for (const child of this.children) {
      if (selector.startsWith('.') ? (child.className || '').split(' ').includes(selector.slice(1)) : child.tag === selector) return child;
      const nested = child.querySelector(selector);
      if (nested) return nested;
    }
    return null;
  }
}

function renderPreview(hosts, fresh = {updatedAt: Date.now(), failed: false}, minimumSeverity = 0) {
  const dom = {createElement: tag => new PopElement(tag), createElementNS: (_, tag) => new PopElement(tag)};
  const preview = vm.createContext({document: dom, hosts, fresh, minimumSeverity});
  vm.runInContext(fs.readFileSync('src/main/resources/static/pop-summary.js', 'utf8'), preview);
  preview.box = new PopElement('section');
  vm.runInContext(`subject = Object.assign(Object.create(PopSummary.prototype), {
    node: {name: 'POP <safe>', groupId:'g'}, box, hosts: () => hosts, groupName: () => 'Group',
    freshness: () => fresh, minimumSeverity: () => minimumSeverity, colors: ['gray','green','neutral','info','warning','average','high','disaster'],
    labels: ['Unknown','OK','Unclassified','Info','Warning','Average','High','Disaster']
  }); subject.render();`, preview);
  return preview;
}

test('render uses shared SVG icons and worst severity; expansion retains a focusable scroll area', () => {
  const preview = renderPreview([host('a', Array.from({length: 7}, (_, i) => issue(String(i), i % 6, 1000)))]);
  assert.equal(preview.box.style['--pop-severity'], 'disaster');
  assert.equal(preview.box.querySelector('.pop-symbol').querySelector('use').attributes.href.split('#')[1], 'pop-router');
  assert.equal(preview.box.querySelector('.pop-incidents').children.length, 5);
  assert.equal(preview.box.querySelector('.pop-metrics').querySelector('svg'), null);
  assert.ok(preview.box.querySelector('.pop-metrics').textContent.includes('Sem incidentes'));
  assert.equal(preview.box.querySelector('.pop-severity').querySelector('svg'), null);
  assert.equal(preview.box.querySelector('time').querySelector('svg'), null);
  assert.equal(preview.box.querySelector('footer').querySelector('svg').attributes['stroke-width'], '1.5');
  assert.equal(preview.box.querySelector('.pop-more').textContent, 'Ver mais 2 incidentes ');
  preview.box.querySelector('.pop-more').onclick();
  const list = preview.box.querySelector('.pop-incidents');
  assert.equal(list.children.length, 7);
  assert.ok(list.className.includes('pop-expanded'));
  assert.equal(list.focused, true);
  assert.equal(preview.box.querySelector('.pop-more'), null);
  assert.ok(preview.box.querySelector('footer').querySelector('svg'));
});

test('loading, unavailable and cached failure states do not invent healthy counts', () => {
  const loading = renderPreview([], {updatedAt: 0, failed: false});
  assert.ok(loading.box.querySelector('.pop-spinner'));
  assert.equal(loading.box.querySelector('.pop-metrics'), null);
  const unavailable = renderPreview([], {updatedAt: 0, failed: true});
  assert.ok(unavailable.box.textContent.includes('Não foi possível atualizar'));
  assert.equal(unavailable.box.querySelector('.pop-empty'), null);
  const cached = renderPreview([host('a', [issue('a', 4, 1000)])], {updatedAt: Date.now(), failed: true});
  assert.ok(cached.box.querySelector('.pop-failure'));
  assert.ok(cached.box.querySelector('.pop-freshness').textContent.includes('Dados desatualizados'));
  const empty = renderPreview([host('a', [])]);
  assert.ok(empty.box.querySelector('.pop-empty').textContent.includes('Nenhum problema ativo identificado no Zabbix.'));
  assert.equal(empty.box.querySelector('.pop-incidents'), null);
});


test('map threshold filters incidents, affected hosts and counts without changing inventory or monitoring', () => {
  const hosts = [host('a', [issue('low', 0, 100)]),
    host('b', [issue('info', 1, 200), issue('low2', 0, 100)]),
    host('c', [issue('low3', 0, 100)], 'unknown'),
    host('d', [issue('low4', 0, 100)], 'disabled')];
  const filtered = context.summarizePop(hosts, 1);
  assert.equal(filtered.total, 4);
  assert.equal(filtered.affected, 1);
  assert.equal(filtered.operational, 1);
  assert.equal(filtered.unknown, 1);
  assert.equal(filtered.disabled, 1);
  assert.equal(filtered.incidents.length, 1);
  assert.equal(filtered.incidents[0].eventId, 'info');
  assert.equal(hosts[1].incidents.length, 2);
  assert.equal(context.summarizePop(hosts, 0).incidents.length, 5);
});

test('rendered badge, severity, list and expansion all use the current map threshold', () => {
  const hosts = [host('a', Array.from({length: 7}, (_, i) => issue(String(i), i === 6 ? 1 : 0, 1000)))];
  const preview = renderPreview(hosts, undefined, 1);
  assert.equal(preview.box.querySelector('.pop-badge').textContent, '1 incidente');
  assert.equal(preview.box.style['--pop-severity'], 'info');
  assert.equal(preview.box.querySelector('.pop-incidents').children.length, 1);
  assert.equal(preview.box.querySelector('.pop-more'), null);
  preview.minimumSeverity = 2;
  vm.runInContext('subject.render()', preview);
  assert.equal(preview.box.querySelector('.pop-badge').textContent, '0 incidentes');
  assert.equal(preview.box.querySelector('.pop-incidents'), null);
  assert.ok(preview.box.querySelector('.pop-empty'));
});
