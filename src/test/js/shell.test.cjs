const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  class Element {
    constructor() { this.children = []; this.listeners = {}; this.classList = { add() {} }; }
    append(...children) { this.children.push(...children); }
    setAttribute() {}
    addEventListener(type, fn) { this.listeners[type] = fn; }
    emit(type) { this.listeners[type]?.(); }
    remove() { this.removed = true; }
    removeAttribute(name) { if (name === 'open') this.open = false; }
    contains(target) { return target === this; }
  }
  const ready = [], listeners = {}, timers = new Map();
  const body = new Element(), menu = new Element();
  menu.open = true;
  let serial = 0;
  const window = {};
  vm.runInNewContext(
    fs.readFileSync(path.join(__dirname, '../../main/resources/static/shell.js'), 'utf8'),
    { window, document: {
      body,
      createElement: () => new Element(),
      querySelector: () => body.children[0],
      querySelectorAll: selector => selector === '.utility-menu[open]' ? [menu] : [],
      addEventListener(type, fn) { if (type === 'DOMContentLoaded') ready.push(fn); else listeners[type] = fn; },
    },
    setTimeout(fn, ms) { const id = ++serial; timers.set(id, { fn, ms }); return id; },
    clearTimeout(id) { timers.delete(id); },
    },
  );
  ready.forEach(fn => fn());
  return { window, body, menu, listeners, timers, run(ms) {
    const entry = [...timers].find(([, timer]) => timer.ms === ms);
    assert.ok(entry, `Expected a ${ms}ms timer`);
    timers.delete(entry[0]);
    entry[1].fn();
  } };
}

test('notification preserves message as text and can be dismissed manually', () => {
  const ui = setup();
  ui.window.notify('<script>unsafe()</script>', 'error');
  const card = ui.body.children[0].children[0];
  assert.equal(card.children[0].textContent, '<script>unsafe()</script>');
  card.children[1].emit('click');
  assert.equal([...ui.timers.values()].some(timer => timer.ms === 5000), false);
  ui.run(220);
  assert.equal(card.removed, true);
});

test('notification expiry pauses during focus and resumes after leaving', () => {
  const ui = setup();
  ui.window.notify('Saved');
  const card = ui.body.children[0].children[0];
  card.emit('focusin');
  assert.equal(ui.timers.size, 0);
  assert.equal(card.removed, undefined);
  card.emit('focusout');
  ui.run(5000);
  ui.run(220);
  assert.equal(card.removed, true);
});

test('escape and outside clicks close menus while inside clicks preserve them', () => {
  const ui = setup();
  ui.listeners.click({ target: ui.menu });
  assert.equal(ui.menu.open, true);
  ui.listeners.keydown({ key: 'Escape' });
  assert.equal(ui.menu.open, false);
  ui.menu.open = true;
  ui.listeners.click({ target: {} });
  assert.equal(ui.menu.open, false);
});
