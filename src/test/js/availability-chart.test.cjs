const test = require('node:test');
const assert = require('node:assert/strict');
const {aggregate, paths, Chart} = require('../../main/resources/static/availability-chart.js');
const point = (from, healthy, degraded, lost) => ({from, to: from + 60000, metrics: {healthy, degraded, lost}});
test('weighted counts preserve operational criteria, chronological gaps and partial edges', () => {
  const report = {from: 30000, to: 930000, series: [point(30000, 1, 0, 0), point(60000, 0, 1, 8), point(900000, 2, 0, 0)]};
  const result = aggregate(report, 5);
  assert.equal(result.length, 4);
  assert.equal(result[0].from, 30000);
  assert.equal(result[0].technical, 20);
  assert.equal(result[0].operational, 10);
  assert.equal(result[1].technical, null);
  assert.equal(result[2].operational, null);
  assert.equal(result[3].to, 930000);
  assert.equal(result[3].technical, 100);
  for (const minutes of [1,5,15,60]) {
    const buckets = aggregate(report, minutes);
    assert.equal(buckets.reduce((n, p) => n + p.healthy + p.degraded + p.lost, 0), 12);
  }
});
test('empty intervals break both the line and the area without inventing points', () => {
  const points = [{from:0,to:1,operational:90},{from:1,to:2,operational:null},{from:2,to:3,operational:80}];
  const result = paths(points, 'operational', x => x, y => y, 100);
  assert.equal((result.line.match(/M/g)||[]).length, 2);
  assert.equal((result.area.match(/Z/g)||[]).length, 2);
  assert.ok(!result.line.includes('NaN'));
});
class Element {
  constructor(tag) { this.tag = tag; this.children = []; this.attributes = {}; this.style = {}; this.listeners = {}; this.value = '1'; }
  append(...els) { this.children.push(...els); }
  replaceChildren(...els) { this.children = els; }
  setAttribute(key, value) { this.attributes[key] = value; }
  addEventListener(type, fn) { this.listeners[type] = fn; }
  getBoundingClientRect() { return this.tag === 'div' ? {left:0,top:0,width:270,height:110} : {left:0,top:0,width:800,height:360}; }
}
test('pointer, touch, keyboard and exit control shared tooltip; renderer stays bounded', () => {
  global.document = {createElementNS: (ns, tag) => new Element(tag), createElement: tag => new Element(tag)};
  const container = new Element('container'), select = new Element('select');
  const chart = new Chart(container, select);
  chart.update({from:0,to:180000,evaluatedAt:180000,series:[point(0,9,1,0),point(60000,0,0,0),point(120000,5,0,5)]});
  const [svg, tooltip] = container.children;
  svg.listeners.pointermove({clientX:100,clientY:100});
  assert.equal(tooltip.hidden, false);
  assert.equal(tooltip.children[1].children[1].textContent, '100,00%');
  assert.equal(tooltip.children[2].children[1].textContent, '90,00%');
  svg.listeners.pointerdown({clientX:420,clientY:100,pointerType:'touch'});
  assert.equal(tooltip.children[1].children[1].textContent, 'Sem dados');
  svg.listeners.pointermove({clientX:780,clientY:300});
  assert.ok(parseFloat(tooltip.style.left) + 270 <= 800);
  assert.ok(parseFloat(tooltip.style.top) + 110 <= 360);
  svg.listeners.pointerleave(); assert.equal(tooltip.hidden, true);
  svg.listeners.keydown({key:'Home',preventDefault(){}}); assert.equal(tooltip.hidden, false);
  svg.listeners.keydown({key:'Escape'}); assert.equal(tooltip.hidden, true);
  chart.update({from:0,to:2880*60000,evaluatedAt:1,series:Array.from({length:2880},(_,i)=>point(i*60000,1,0,0))});
  assert.ok(container.children[0].children.length < 50);
  select.value='60'; select.listeners.change();
  assert.equal(chart.report.to,2880*60000);
  delete global.document;
});
