const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(multiple = false) {
  class Element {
    constructor() {
      this.children = [];
      this.listeners = {};
      this.value = '';
      this.classList = { add() {}, toggle() {} };
    }
    append(...children) { this.children.push(...children); }
    replaceChildren() { this.children = []; }
    setAttribute(key, value) { (this.attributes ||= {})[key] = value; }
    getAttribute(key) { return this.attributes?.[key]; }
    removeAttribute() {}
    addEventListener(type, callback) { this.listeners[type] = callback; }
    emit(type, key) { this.listeners[type]?.({ key, preventDefault() {}, stopPropagation() {} }); }
    dispatchEvent(event) { this.changes = (this.changes || 0) + 1; this.emit(event.type); }
    focus() { this.focused = true; }
    scrollIntoView() {}
  }
  const select = new Element();
  select.multiple = multiple;
  select.parentElement = new Element();
  select.options = [
    { value: '', textContent: 'Todos os hosts' },
    { value: 'a', textContent: 'Produção' },
    { value: 'b', textContent: 'Filial' },
  ];
  Object.defineProperty(select, 'selectedOptions', {
    get() { return select.options.filter(option => multiple ? option.selected : option.value === select.value); },
  });
  const window = {};
  vm.runInNewContext(
    fs.readFileSync(path.join(__dirname, '../../main/resources/static/group-picker.js'), 'utf8'),
    { window, Event, document: {
      getElementById: () => select,
      createElement: () => new Element(),
      createTextNode: text => text,
      addEventListener() {},
    } },
  );
  const [trigger, panel] = select.parentElement.children;
  const [input, list] = panel.children;
  return { select, trigger, panel, input, list, sync: window.availabilityGroupPicker.sync };
}

test('search normalizes accents but changes selection only after confirmation', () => {
  const ui = setup();
  ui.trigger.emit('click');
  ui.input.value = '  PRODUCAO  ';
  ui.input.emit('input');
  assert.equal(ui.select.value, '');
  assert.equal(ui.select.changes || 0, 0);
  ui.input.emit('keydown', 'Enter');
  assert.equal(ui.select.value, 'a');
  assert.equal(ui.select.changes, 1);
  assert.equal(ui.trigger.focused, true);
});

test('escape and no-match confirmation preserve the current filter', () => {
  const ui = setup();
  ui.select.value = 'b';
  ui.trigger.emit('click');
  ui.input.value = 'missing';
  ui.input.emit('input');
  ui.input.emit('keydown', 'Enter');
  assert.equal(ui.select.value, 'b');
  ui.input.emit('keydown', 'Escape');
  assert.equal(ui.select.value, 'b');
  assert.equal(ui.select.changes || 0, 0);
});

test('confirming the same option does not trigger a duplicate request', () => {
  const ui = setup();
  ui.trigger.emit('click');
  ui.input.emit('keydown', 'Enter');
  assert.equal(ui.select.value, '');
  assert.equal(ui.select.changes || 0, 0);
});

test('keyboard navigation stays within results and picks the active option', () => {
  const ui = setup();
  ui.trigger.emit('keydown', 'ArrowDown');
  for (let i = 0; i < 10; i++) ui.input.emit('keydown', 'ArrowDown');
  ui.input.emit('keydown', 'Enter');
  assert.equal(ui.select.value, 'b');
  assert.equal(ui.select.changes, 1);
});

test('refreshed inventory replaces stale choices while the picker is open', () => {
  const ui = setup();
  ui.trigger.emit('click');
  ui.select.options = [{ value: 'new', textContent: 'Novo grupo' }];
  ui.sync();
  ui.input.emit('keydown', 'Enter');
  assert.equal(ui.select.value, 'new');
  assert.equal(ui.select.changes, 1);
});


test('multiple selection keeps the menu open and supports selecting all groups', () => {
  const ui = setup(true);
  ui.trigger.emit('click');
  ui.list.children[1].emit('click');
  ui.list.children[2].emit('click');
  assert.equal(ui.select.selectedOptions.length, 2);
  assert.equal(ui.panel.hidden, false);
  assert.equal(ui.trigger.children[0].textContent, '2 grupos selecionados');
  ui.list.children[0].emit('click');
  assert.equal(ui.select.selectedOptions.length, 0);
  ui.list.children[0].emit('click');
  assert.equal(ui.select.selectedOptions.length, 2);
});
