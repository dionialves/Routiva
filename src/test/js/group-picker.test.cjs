const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(multiple = false, bounds = null) {
  class Element {
    constructor() {
      this.children = [];
      this.listeners = {};
      this.value = '';
      this.style = {};
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
    getBoundingClientRect() { return bounds; }
    showPopover() { this.popoverOpen = true; }
    hidePopover() { this.popoverOpen = false; }
    matches() { return !!this.popoverOpen; }
  }
  const select = new Element();
  if (bounds) select.closest = () => new Element();
  select.id = 'group';
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
  const window = {innerWidth: 1000, innerHeight: 800, addEventListener() {}};
  vm.runInNewContext(
    fs.readFileSync(path.join(__dirname, '../../main/resources/static/group-picker.js'), 'utf8'),
    { window, Event, document: {
      getElementById: () => select,
      createElement: () => new Element(),
      createElementNS: () => new Element(),
      createTextNode: text => text,
      addEventListener() {},
    } },
  );
  const [trigger, panel] = select.parentElement.children;
  const [input, list] = panel.children;
  return { select, trigger, panel, input, list, sync: window.availabilityGroupPicker.sync, setupPicker: window.setupGroupPicker, Element };
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

test('search confirmation selects matching groups instead of toggling them off', () => {
  const ui = setup(true);
  ui.select.options[1].textContent = '177 - Produção';
  ui.select.options[2].textContent = '177 - Filial';
  ui.select.options.push({ value: 'c', textContent: 'Outro grupo', selected: true });
  ui.select.options[1].selected = true;
  ui.trigger.emit('click');
  ui.input.value = '177';
  ui.input.emit('input');
  assert.equal(ui.select.options[3].selected, true);
  ui.input.emit('keydown', 'Enter');
  assert.deepEqual(ui.select.selectedOptions.map(option => option.value), ['a', 'b']);
  assert.equal(ui.panel.hidden, false);
  assert.equal(ui.select.changes, 1);
  ui.input.emit('keydown', 'Enter');
  assert.equal(ui.select.changes, 1);
  ui.input.value = 'missing';
  ui.input.emit('input');
  ui.input.emit('keydown', 'Enter');
  assert.deepEqual(ui.select.selectedOptions.map(option => option.value), ['a', 'b']);
});

test('no explicit group selection displays all hosts without checked groups', () => {
  const ui = setup(true);
  assert.equal(ui.trigger.children[0].textContent, 'Todos os hosts');
  assert.equal(ui.select.selectedOptions.length, 0);
});


test('host pickers reuse searchable single selection with independent accessible IDs', () => {
  const ui=setup();
  const select=new ui.Element();
  select.id='connection-host-select-a';
  select.parentElement=new ui.Element();
  select.options=[{value:'',textContent:'Selecione o host'},{value:'radio',textContent:'Rádio Core'}];
  Object.defineProperty(select,'selectedOptions',{get(){return select.options.filter(option=>option.value===select.value);}});
  ui.setupPicker(select,'connection-host-label-a','Pesquisar host do grupo',{listLabel:'Hosts do grupo',emptyText:'Nenhum host encontrado.'});
  const [trigger,panel]=select.parentElement.children;
  const [input,list,empty]=panel.children;
  assert.equal(trigger.attributes['aria-labelledby'],'connection-host-label-a connection-host-select-a-selection');
  assert.equal(list.id,'connection-host-select-a-results');
  assert.equal(ui.list.id,'group-results');
  assert.equal(empty.textContent,'Nenhum host encontrado.');
  trigger.emit('click');
  input.value='RADIO';input.emit('input');input.emit('keydown','Enter');
  assert.equal(select.value,'radio');
  assert.equal(ui.select.value,'');
});


test('dialog host picker follows its own trigger and opens above when space below is limited', () => {
  const ui = setup(false, {left:160,top:600,bottom:640,width:340});
  ui.panel.scrollHeight=320;
  ui.trigger.emit('click');
  assert.equal(ui.panel.popoverOpen,true);
  assert.equal(ui.panel.style.left,'160px');
  assert.equal(ui.panel.style.width,'340px');
  assert.equal(ui.panel.style.top,'auto');
  assert.equal(ui.panel.style.bottom,'206px');
});

test('dialog picker stays within the viewport and clears its bottom position when opening below', () => {
  const bounds={left:850,top:600,bottom:640,width:340};
  const ui=setup(false,bounds);
  ui.panel.scrollHeight=320;
  ui.trigger.emit('click');
  assert.equal(ui.panel.style.left,'654px');
  ui.input.emit('keydown','Escape');
  Object.assign(bounds,{left:100,top:100,bottom:140});
  ui.trigger.emit('click');
  assert.equal(ui.panel.style.left,'100px');
  assert.equal(ui.panel.style.top,'146px');
  assert.equal(ui.panel.style.bottom,'auto');
});
