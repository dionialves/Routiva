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
      AvailabilityChart:{ ...require('../../main/resources/static/availability-chart.js'),
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
  assert.ok(ui.elements['latency-chart'].children[0].children.some(node => node.stroke === '#f59e0b'));
});
test('missing measurements show empty states and clearing removes the previous report', () => {
  const ui = surface();
  ui.charts.update({ from:0, to:60000, evaluatedAt:null, series:[] });
  assert.equal(ui.elements['availability-chart'].children[0].textContent, 'Sem medições no período.');
  assert.equal(ui.elements['latency-chart'].children[0].textContent, 'Sem medições no período.');
  ui.charts.clear('Histórico indisponível.');
  assert.equal(ui.primary.report, null);
  assert.equal(ui.elements['latency-chart'].children[0].textContent, 'Histórico indisponível.');
});

test('missing bands retain the requested timeline and include only gaps', () => {
  const { missingArea } = require('../../main/resources/static/availability-chart.js');
  const points = [
    { from:10, to:20, latency:null, loss:100 },
    { from:20, to:30, latency:null, loss:null },
    { from:30, to:40, latency:0, loss:0 },
  ];
  assert.equal(missingArea(points, ['latency','loss'], 0, 50, value => value, 0, 20),
    'M0,0H10V20H0Z M20,0H30V20H20Z M40,0H50V20H40Z ');
});

test('wireless ping rounds latency through measured values and keeps missing samples as gaps', () => {
  const ui = surface();
  const container = ui.elements['wireless-ping-chart'];
  const chart = ui.charts.createRealtime(container);
  chart.update({ from:0, to:5000, series:[8, 40, null, 0, 12].map((latency, i) => ({
    from:i * 1000, to:(i + 1) * 1000, metrics:{ latency, loss:latency == null ? 100 : 0 },
  })) });
  const paths = container.children[0].children;
  const latency = paths.find(node => node.stroke === '#06b6d4' && node.d);
  const loss = paths.find(node => node.stroke === '#f59e0b');
  assert.equal((latency.d.match(/M/g) || []).length, 2);
  assert.equal((latency.d.match(/C/g) || []).length, 2);
  assert.ok(!loss.d.includes('C'));
  const historical = ui.elements['latency-chart'];
  ui.charts.update({ from:0, to:2000, series:[8, 40].map((latency, i) => ({
    from:i * 1000, to:(i + 1) * 1000, metrics:{ latency, loss:0 },
  })) });
  assert.ok(!historical.children[0].children.find(node => node.stroke === '#06b6d4' && node.d).d.includes('C'));
});
