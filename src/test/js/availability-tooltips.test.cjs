const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
function surface() {
  const timers = new Map();
  function element(bounds) {
    return {listeners:{}, style:{}, attrs:{}, open:false, focused:false,
      addEventListener(name, fn) { this.listeners[name] = fn; },
      getAttribute(name) { return this.attrs[name]; },
      setAttribute(name, value) { this.attrs[name] = value; },
      matches(selector) { return selector === ':popover-open' ? this.open : this.focused; },
      getBoundingClientRect() { return bounds; },
      showPopover() { this.open = true; this.emit('toggle'); },
      hidePopover() { this.open = false; this.emit('toggle'); },
      contains(target) { return target === this; },
      emit(name, event = {}) { this.listeners[name]?.({preventDefault() {}, ...event}); }
    };
  }
  const trigger = element({left:280, top:400, bottom:440});
  trigger.attrs['aria-controls'] = 'details';
  const tooltip = element({width:300, height:180});
  const window = element({}); window.innerWidth=360; window.innerHeight=500;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/availability-tooltips.js'), 'utf8'), {
    document:{querySelectorAll:()=>[trigger], getElementById:()=>tooltip}, window,
    setTimeout(fn) { timers.set(1, fn); return 1; }, clearTimeout(id) { timers.delete(id); }
  });
  return {trigger, tooltip, window, flush() { for (const fn of timers.values()) fn(); timers.clear(); }};
}
test('hover details stay within viewport and remain open when entering tooltip', () => {
  const ui=surface(); ui.trigger.emit('pointerenter', {pointerType:'mouse'});
  assert.equal(ui.tooltip.open,true); assert.equal(ui.trigger.attrs['aria-expanded'],'true');
  assert.equal(ui.tooltip.style.left,'52px'); assert.equal(ui.tooltip.style.top,'220px');
  ui.trigger.emit('pointerleave'); ui.tooltip.emit('pointerenter'); ui.flush();
  assert.equal(ui.tooltip.open,true);
  ui.tooltip.emit('pointerleave'); ui.flush(); assert.equal(ui.tooltip.open,false);
});
test('keyboard focus opens details and blur closes; touch click toggles details', () => {
  const ui=surface(); ui.trigger.emit('focus'); assert.equal(ui.tooltip.open,true);
  ui.trigger.emit('blur'); assert.equal(ui.tooltip.open,false);
  ui.trigger.emit('pointerenter', {pointerType:'touch'}); assert.equal(ui.tooltip.open,false);
  ui.trigger.emit('focus'); ui.trigger.emit('click'); ui.trigger.emit('pointerleave'); ui.flush();
  assert.equal(ui.tooltip.open,true);
  ui.trigger.emit('click'); assert.equal(ui.tooltip.open,false);
  // Native light-dismiss/Escape emits toggle, resetting state for the next tap.
  ui.trigger.emit('click'); ui.tooltip.hidePopover();
  assert.equal(ui.trigger.attrs['aria-expanded'],'false');
  ui.trigger.emit('click'); assert.equal(ui.tooltip.open,true);
  ui.window.emit('scroll', {target:{}}); assert.equal(ui.tooltip.open,false);
});
