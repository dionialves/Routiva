const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('modal tabs reveal selection horizontally on activation, opening and resize', () => {
  let observer;
  let resize;
  let selected = 'true';
  let left = 250;
  const list = { scrollLeft: 0, getBoundingClientRect: () => ({ left: 0, right: 200 }) };
  const tab = {
    closest: () => list,
    getAttribute: () => selected,
    getBoundingClientRect: () => ({ left, right: left + 100 }),
  };
  const document = { body: {}, querySelectorAll: () => [tab] };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/modal-tabs.js', 'utf8'), {
    document,
    window: { addEventListener: (name, callback) => { if (name === 'resize') resize = callback; } },
    MutationObserver: class {
      constructor(callback) { observer = callback; }
      observe() {}
    },
  });
  assert.equal(list.scrollLeft, 150);
  left = -50;
  observer([{ attributeName: 'aria-selected', target: tab }]);
  assert.equal(list.scrollLeft, 100);
  selected = 'false';
  observer([{ attributeName: 'aria-selected', target: tab }]);
  assert.equal(list.scrollLeft, 100);
  selected = 'true';
  left = 180;
  observer([{ attributeName: 'open' }]);
  assert.equal(list.scrollLeft, 180);
  left = 20;
  resize();
  assert.equal(list.scrollLeft, 180);
});
