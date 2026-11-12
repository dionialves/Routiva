const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
function surface() {
  class Element {
    constructor() { this.children = []; this.dataset = {}; this.style = {}; this.events = {}; this.attrs = {}; this.isConnected = true; }
    append(...nodes) { this.children.push(...nodes); }
    prepend(...nodes) { this.children.unshift(...nodes); }
    replaceChildren(...nodes) { this.children = nodes.flatMap(node => node.fragment ? node.children : [node]); }
    setAttribute(key, value) { this.attrs[key] = value; }
    removeAttribute(key) { delete this.attrs[key]; }
    addEventListener(name, fn) { this.events[name] = fn; }
    emit(name, event = {}) { this.events[name]?.(event); }
    contains(target) { return target === this; }
    closest() { return null; }
    getBoundingClientRect() { return this.bounds || { width:376, height:360 }; }
  }
  const timers = new Map(); let serial = 0;
  const body = new Element(), document = new Element(), window = new Element();
  Object.assign(document, { body, documentElement:{ clientWidth:800 },
    createElement:() => new Element(), createElementNS:() => new Element(),
    createTextNode:text => Object.assign(new Element(), { textContent:text }),
    createDocumentFragment:() => Object.assign(new Element(), { fragment:true }) });
  window.innerHeight = 600;
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/host-wireless-tooltip.js', 'utf8'), {
    window, document, setTimeout(fn, ms) { timers.set(++serial, { fn, ms }); return serial; }, clearTimeout(id) { timers.delete(id); },
  });
  const tooltip = window.HostWirelessTooltip.create();
  const row = new Element(); row.bounds = { left:400, right:780, top:550 }; row.dataset = { clientMac:'AA', clientInterface:'wlan1' };
  const client = { 'mac-address':'AA', interface:'wlan1', quality:'Crítico', 'quality-severity':'3', 'avg-signal':'-74.67', 'avg-tx-ccq':'56.08', 'tx-traffic-bps':'3005410', 'rx-traffic-bps':'1000000', 'quality-summary':'Degradação identificada sob tráfego.', 'quality-primary-reason':'TX CCQ reduzido sob tráfego significativo.' };
  tooltip.bind(row, client, true);
  return { tooltip, row, client, card:body.children[0], timers, document, makeRow:() => Object.assign(new Element(), { dataset:{...row.dataset}, bounds:row.bounds }), flush() { const pending = [...timers.values()]; timers.clear(); pending.forEach(timer => timer.fn()); } };
}
test('delayed hover cancels quick passes and stays open across the portal gap', () => {
  const ui = surface(); ui.row.emit('pointerenter', { pointerType:'mouse', clientX:400, clientY:550 });
  assert.equal([...ui.timers.values()][0].ms, 200); assert.equal(ui.card.hidden, true);
  ui.row.emit('pointerleave'); ui.flush(); assert.equal(ui.card.hidden, true);
  ui.row.emit('pointerenter', { pointerType:'mouse', clientX:400, clientY:550 }); ui.flush();
  assert.equal(ui.card.hidden, false);
  assert.equal(ui.card.style.left, '412px'); assert.equal(ui.card.style.top, '232px');
  ui.row.emit('pointerleave'); ui.card.emit('pointerenter'); ui.flush(); assert.equal(ui.card.hidden, false);
  ui.card.emit('pointerleave'); ui.flush(); assert.equal(ui.card.hidden, true);
});
test('focus, touch and Escape expose and dismiss the diagnosis', () => {
  const ui = surface(); ui.row.emit('focusin'); ui.flush(); assert.equal(ui.card.hidden, false);
  assert.equal(ui.row.attrs['aria-describedby'], ui.card.id);
  ui.document.emit('keydown', { key:'Escape' }); assert.equal(ui.card.hidden, true);
  ui.row.emit('pointerup', { pointerType:'touch', target:ui.row }); ui.flush(); assert.equal(ui.card.hidden, false);
});
test('live refresh keeps the same card and position, uses means and excludes RX CCQ', () => {
  const ui = surface(); ui.row.emit('focusin'); ui.flush();
  const metrics = ui.card.children[2]; assert.equal(metrics.children.length, 4);
  assert.equal(metrics.children[0].children[1].textContent, '-74,67 dBm');
  assert.equal(metrics.children[3].children[1].textContent, '4,01 Mbps');
  const replacement = ui.makeRow(), left = ui.card.style.left;
  const updated = { ...ui.client, quality:'Normal', 'quality-severity':'0', 'avg-rx-ccq':'92', 'avg-tx-ccq':'93', 'quality-primary-reason':'Conexão dentro dos parâmetros.' };
  ui.tooltip.update([updated], [replacement], true);
  assert.equal(ui.card.hidden, false); assert.equal(ui.card.style.left, left);
  assert.equal(metrics.children.length, 4); assert.equal(ui.card.children[0].children[2].textContent, 'Normal');
  assert.equal(replacement.attrs['aria-describedby'], ui.card.id);
  ui.tooltip.update([], [], true); assert.equal(ui.card.hidden, true);
});

test('card opens at the latest cursor position and flips at the viewport edge', () => {
  const ui = surface();
  ui.row.emit('pointerenter', { pointerType:'mouse', clientX:100, clientY:80 });
  ui.row.emit('pointermove', { pointerType:'mouse', clientX:150, clientY:100 });
  ui.flush();
  assert.equal(ui.card.style.left, '162px'); assert.equal(ui.card.style.top, '112px');
  ui.row.emit('pointermove', { pointerType:'mouse', clientX:780, clientY:580 });
  assert.equal(ui.card.style.left, '392px'); assert.equal(ui.card.style.top, '232px');
});

test('aggregate traffic requires both directions and preserves measured zero', () => {
  const ui = surface(); ui.row.emit('focusin'); ui.flush();
  const value = () => ui.card.children[2].children[3].children[1].textContent;
  assert.equal(ui.card.children[2].children[3].children[0].textContent, 'Tráfego total atual');
  ui.tooltip.update([{ ...ui.client, 'rx-traffic-bps':undefined }], [ui.row], true);
  assert.equal(value(), '—');
  ui.tooltip.update([{ ...ui.client, 'tx-traffic-bps':'0', 'rx-traffic-bps':'0' }], [ui.row], true);
  assert.equal(value(), '0 kbps');
});

test('aggregate traffic switches to Mbps only above 2 Mbps', () => {
  const ui = surface(); ui.row.emit('focusin'); ui.flush();
  const value = () => ui.card.children[2].children[3].children[1].textContent;
  ui.tooltip.update([{ ...ui.client, 'tx-traffic-bps':'1500000', 'rx-traffic-bps':'500000' }], [ui.row], true);
  assert.equal(value(), '2.000 kbps');
  ui.tooltip.update([{ ...ui.client, 'tx-traffic-bps':'1500000', 'rx-traffic-bps':'501000' }], [ui.row], true);
  assert.equal(value(), '2 Mbps');
  ui.tooltip.update([{ ...ui.client, 'tx-traffic-bps':'500000', 'rx-traffic-bps':'250000' }], [ui.row], true);
  assert.equal(value(), '750 kbps');
});
