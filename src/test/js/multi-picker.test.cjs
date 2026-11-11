const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function surface(kind = 'group') {
  class Element {
    constructor() { this.children = []; this.listeners = {}; this.value = ''; }
    append(...nodes) { this.children.push(...nodes); }
    addEventListener(type, listener) { this.listeners[type] = listener; }
    focus() { this.focused = true; }
    querySelector() { return this.summary ||= new Element(); }
  }
  const elements = new Proxy({}, { get: (target, id) => target[id] ||= new Element() });
  const context = { document: { getElementById: id => elements[id], createElement: () => new Element(), addEventListener() {} } };
  context.window = context;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/multi-picker.js'), 'utf8'), context);
  const options = [
    { textContent: 'POP A', selected: false },
    { textContent: 'POP B', selected: false },
    { textContent: 'Outro', selected: true },
    { textContent: 'POP indisponível', disabled: true, selected: false },
  ];
  let changes = 0;
  context.setupMultiPicker(kind, { options }, 'Todos', () => changes++);
  const search = elements[`incident-${kind}-search`];
  const menu = elements[`incident-${kind}-picker`];
  const enter = extra => {
    const event = { key: 'Enter', preventDefault() { this.prevented = true; }, stopPropagation() { this.stopped = true; }, ...extra };
    search.listeners.keydown(event);
    return event;
  };
  return { options, search, menu, enter, changes: () => changes };
}

for (const kind of ['group', 'host']) {
  test(`Enter selects visible ${kind} results, replaces previous selections and closes the picker`, () => {
    const ui = surface(kind);
    ui.menu.open = true;
    ui.search.value = 'pop';
    const event = ui.enter();
    assert.deepEqual(ui.options.map(option => option.selected), [true, true, false, false]);
    assert.equal(ui.changes(), 1);
    assert.equal(ui.menu.open, false);
    assert.equal(ui.menu.querySelector().focused, true);
    assert.equal(event.prevented, true);
    assert.equal(event.stopped, true);
  });
}

test('Enter with no matches preserves the selection without triggering a change', () => {
  const ui = surface();
  ui.menu.open = true;
  ui.search.value = 'inexistente';
  ui.enter();
  assert.deepEqual(ui.options.map(option => option.selected), [false, false, true, false]);
  assert.equal(ui.changes(), 0);
  assert.equal(ui.menu.open, false);
});

test('Enter during text composition does not confirm the selection', () => {
  const ui = surface();
  ui.menu.open = true;
  ui.enter({ isComposing: true });
  assert.equal(ui.changes(), 0);
  assert.equal(ui.menu.open, true);
});

test('Enter narrows an existing selection of every option to the search matches', () => {
  const ui = surface();
  ui.options.filter(option => !option.disabled).forEach(option => option.selected = true);
  ui.search.value = 'POP A';
  ui.enter();
  assert.deepEqual(ui.options.map(option => option.selected), [true, false, false, false]);
  assert.equal(ui.changes(), 1);
});
