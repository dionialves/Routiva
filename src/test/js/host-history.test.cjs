const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
function surface() {
  class Element {
    constructor() { this.children = []; this.style = {}; this.classList = { toggle() {} }; }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
    setAttribute(key, value) { this[key] = value; }
    getBoundingClientRect() { return { width:700, left:0 }; }
    addEventListener() {}
    closest() { return this; }
  }
  const elements = new Proxy({}, { get(object, key) { return object[key] ||= new Element(); } });
  let primary;
  const context = {
    document:{ getElementById:id => elements[id], createElement:() => new Element(), createElementNS:() => new Element(), addEventListener() {}, body:new Element() },
    window:{ AvailabilityView:{ format:(value, unit) => value == null ? 'Sem dados' : value + unit },
      AvailabilityChart:{ paths:require('../../main/resources/static/availability-chart.js').paths,
        Chart:class { constructor() { primary = this; } clear() { this.report = null; } update(report) { this.report = report; } draw() {} } }, addEventListener() {} },
    Date,
  };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/host-history.js', 'utf8'), context);
  return { elements, primary, charts:context.window.HostHistoryCharts };
}
test('historical charts use the report for availability, latency and loss', () => {
  const ui = surface();
  const report = { from:0, to:60000, evaluatedAt:60000, series:[{ from:0, to:60000, metrics:{ technical:100, operational:99, latency:2, loss:1 } }] };
  ui.charts.update(report);
  assert.equal(ui.primary.report, report);
  assert.equal(ui.elements['latency-chart'].children[0].role, 'group');
  assert.equal(ui.elements['loss-chart'].children[0].role, 'group');
  ui.elements['chart-bars'].onclick();
  assert.equal(ui.primary.mode, 'bars');
  ui.elements['chart-expand'].onclick();
  assert.equal(ui.primary.expanded, true);
});
test('missing measurements show empty states and clearing removes the previous report', () => {
  const ui = surface();
  ui.charts.update({ from:0, to:60000, evaluatedAt:null, series:[] });
  assert.equal(ui.elements['availability-chart'].children[0].textContent, 'Sem medições no período.');
  assert.equal(ui.elements['latency-chart'].children[0].textContent, 'Sem medições no período.');
  ui.charts.clear('Histórico indisponível.');
  assert.equal(ui.primary.report, null);
  assert.equal(ui.elements['loss-chart'].children[0].textContent, 'Histórico indisponível.');
});
