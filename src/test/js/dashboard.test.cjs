const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(
  path.join(__dirname, '../../main/resources/static/dashboard.js'),
  'utf8',
);

function surface(storage = new Map(), blockStorage = false) {
  function element() {
    return {
      listeners: {},
      attributes: {},
      value: '',
      textContent: '',
      disabled: false,
      addEventListener(name, fn) {
        (this.listeners[name] ??= []).push(fn);
      },
      emit(name, detail) {
        for (const fn of this.listeners[name] ?? []) fn({ detail });
      },
      setAttribute(name, value) {
        this.attributes[name] = value;
      },
    };
  }
  const ids = [
    'dashboard',
    'filters',
    'refresh-interval',
    'refresh-now',
    'connection',
    'sort-column',
    'sort-direction',
  ];
  const elements = Object.fromEntries(ids.map((id) => [id, element()]));
  const document = element();
  document.body = element();
  document.getElementById = (id) => elements[id];
  const timers = new Map();
  const requests = [];
  let serial = 0;
  const htmx = {
    config: {},
    trigger(elt, name) {
      requests.push({ elt, name });
      document.body.emit('htmx:beforeRequest', { elt });
    },
  };
  vm.runInNewContext(source, {
    document,
    htmx,
    localStorage: {
      getItem(key) {
        if (blockStorage) throw Error('Blocked');
        return storage.get(key) ?? null;
      },
      setItem(key, value) {
        if (blockStorage) throw Error('Blocked');
        storage.set(key, value);
      },
    },
    setTimeout(fn, delay) {
      timers.set(++serial, { fn, delay });
      return serial;
    },
    clearTimeout(id) {
      timers.delete(id);
    },
  });
  document.emit('DOMContentLoaded');
  return {
    ...elements,
    document,
    timers,
    requests,
    delay() {
      assert.equal(timers.size, 1);
      return [...timers.values()][0].delay;
    },
    tick() {
      const [id, timer] = [...timers.entries()][0];
      timers.delete(id);
      timer.fn();
    },
    choose(value) {
      elements['refresh-interval'].value = String(value);
      elements['refresh-interval'].emit('change');
    },
    complete(successful = true, elt = elements.dashboard) {
      document.body.emit('htmx:afterRequest', { elt, successful });
    },
  };
}

test('all four intervals replace the timer and persist across page navigation', () => {
  const saved = new Map();
  const page = surface(saved);
  assert.equal(page.delay(), 5000);
  for (const seconds of [5, 10, 15, 60]) {
    page.choose(seconds);
    assert.equal(page.delay(), seconds * 1000);
    assert.equal(saved.get('routiva.refreshSeconds'), String(seconds));
  }
  const otherPage = surface(saved);
  assert.equal(otherPage.delay(), 60000);
  assert.equal(otherPage['refresh-interval'].value, '60');
});

test('manual refresh is immediate, avoids overlapping requests and restarts the chosen interval', () => {
  const page = surface();
  page.choose(15);
  page['refresh-now'].emit('click');
  assert.equal(page.requests.length, 1);
  assert.equal(page.requests[0].name, 'refresh');
  assert.equal(page.requests[0].elt, page.dashboard);
  assert.equal(page['refresh-now'].disabled, true);
  assert.equal(page.dashboard.attributes['aria-busy'], 'true');
  assert.equal(page.timers.size, 0);
  page['refresh-now'].emit('click');
  assert.equal(page.requests.length, 1);
  page.complete();
  assert.equal(page.delay(), 15000);
  assert.equal(page['refresh-now'].disabled, false);
  page.tick();
  assert.equal(page.requests.length, 2);
  assert.equal(page.timers.size, 0);
});

test('filter requests also suspend polling and failures resume automatically', () => {
  const page = surface();
  page.choose(10);
  page.document.body.emit('htmx:beforeRequest', { elt: page.filters });
  assert.equal(page.timers.size, 0);
  page['refresh-now'].emit('click');
  assert.equal(page.requests.length, 0);
  page.complete(false, page.filters);
  assert.match(page.connection.textContent, /Falha ao atualizar/);
  assert.equal(page.delay(), 10000);
  page.tick();
  page.complete();
  assert.equal(page.connection.textContent, '');
  assert.equal(page.delay(), 10000);
});

test('blocked storage and invalid saved intervals fall back to five seconds', () => {
  const blocked = surface(new Map(), true);
  assert.equal(blocked.delay(), 5000);
  blocked.choose(60);
  assert.equal(blocked.delay(), 60000);
  const invalid = surface(new Map([['routiva.refreshSeconds', 'invalid']]));
  assert.equal(invalid.delay(), 5000);
  invalid.choose(1);
  assert.equal(invalid.delay(), 5000);
});

test('clicking a column submits the current filters with the new sorting', () => {
  const page = surface();
  let prevented = false;
  const link = {
    dataset: { sort: 'lossHour', direction: 'desc' },
    classList: { contains: () => false },
  };
  page.document.body.listeners.click[0]({
    target: { closest: () => link },
    button: 0,
    preventDefault() {
      prevented = true;
    },
  });
  assert.equal(prevented, true);
  assert.equal(page['sort-column'].value, 'lossHour');
  assert.equal(page['sort-direction'].value, 'desc');
  assert.equal(page.requests[0].elt, page.filters);
  assert.equal(page.requests[0].name, 'submit');
  page.complete(true, page.filters);
  page.tick();
  assert.equal(page['sort-column'].value, 'lossHour');
  assert.equal(page['sort-direction'].value, 'desc');
});
