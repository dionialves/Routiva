const {test} = require('node:test');
const assert = require('node:assert/strict');
const traffic = require('../../main/resources/static/map-traffic.js');
test('utilization boundaries and proportional pulse spacing', () => {
  assert.deepEqual([0, 59.9, 60, 79.9, 80, 89.9, 90, 100, 120].map(n => traffic.utilizationColor(n, 100)),
    ['#22C55E', '#22C55E', '#EAB308', '#EAB308', '#F97316', '#F97316', '#EF4444', '#EF4444', '#EF4444']);
  assert.equal(traffic.utilizationColor(null, 100), null);
  assert.equal(traffic.utilizationColor(50, null), null);
  assert.deepEqual([20, 110, 440, 660].map(traffic.count), [1, 1, 4, 6]);
});
test('missing, reset and invalid rates stay distinct from zero', () => {
  for (const n of [null, undefined, '', 'NaN', '-1', Infinity]) assert.equal(traffic.number(n), null);
  assert.equal(traffic.rate(null), 'Sem dados');
  assert.equal(traffic.rate('0'), '0 Mbps');
  assert.equal(traffic.rate('1000000000'), '1 Gbps');
});
test('stale samples and API errors cannot animate or imply DOWN', () => {
  const endpoint = {measuredAt: 10000, up: true, txBps: '500', rxBps: '0', capacityBps: 1000};
  assert.deepEqual(traffic.state({a: endpoint}, 11000), {down:false, tx:500, rx:0, capacity:1000});
  assert.equal(traffic.state({a: endpoint}, 41000).tx, null);
  assert.equal(traffic.state({a: {...endpoint, up:false, error:'API'}}, 11000).down, false);
  const down = traffic.state({a: endpoint, b:{...endpoint, up:false}}, 11000);
  assert.equal(down.down, true);
  assert.equal(down.tx, null);
});
function element(tag, attrs = {}) {
  return {tag, attrs, children: [], parentNode: null,
    append(...children) { children.forEach(child => { child.parentNode = this; this.children.push(child); }); },
    replaceChildren() { this.children = []; },
    getAttribute(key) { return this.attrs[key]; },
    setAttribute(key, value) { this.attrs[key] = value; },
    getTotalLength() { return attrs.length || 300; },
    style: {setProperty(key, value) { attrs[key] = value; }},
    querySelectorAll() { return []; },
    contains() { return false; },
    classList: {toggle(name, value) { attrs[name] = value; }}};
}
test('pulses follow real geometry, preserve phase across metrics and respect direction', () => {
  const layer = element('g'); layer.parentNode = element('g');
  const d = 'M 0 0 L 0 80 Q 0 100 20 100 L 200 100';
  const path = element('path', {d});
  const a = {up:true, measuredAt:Date.now(), txBps:20, rxBps:80, capacityBps:100};
  const route = [{x:0,y:0},{x:0,y:100},{x:200,y:100}];
  traffic.render(element, layer, path, route, [], {a}, true);
  const pulses = layer.trafficPulses;
  assert.equal(pulses.children.length, 12);
  assert.ok(pulses.children.every(n => n.tag === 'path' && n.attrs.d === d));
  assert.ok(pulses.children.every(n => n.attrs.style.includes('animation-direction:normal')));
  traffic.render(element, layer, path, route, [], {a:{...a, txBps:90}}, true);
  assert.equal(layer.trafficPulses, pulses);
  assert.equal(path.attrs['--traffic-color'], '#EF4444');
  assert.equal(layer.children.some(child => child.tag === 'text'), false);
  traffic.render(element, layer, path, route, [], {a}, false);
  assert.equal(layer.trafficPulses, pulses);
  assert.equal(pulses.attrs.visibility, 'hidden');
  traffic.render(element, layer, path, route, [], {a}, true, true);
  assert.ok(layer.trafficPulses.children.every(n => n.attrs.style.includes('animation-direction:reverse')));
  traffic.render(element, layer, path, route, [], {a:{...a, txBps:0, rxBps:80}}, true, true);
  assert.equal(layer.trafficPulses.attrs.visibility, 'visible');
});

test('missing readings do not preserve a stale DOWN state', () => {
  const layer = element('g'); layer.parentNode = element('g');
  layer.trafficContext = {link:{mappedConnectionId:'down-link'}};
  const path = element('path', {d:'M 0 0 L 200 0'});
  const a = {measuredAt:Date.now(), up:false, txBps:80, rxBps:50, capacityBps:100};
  const route = [{x:0,y:0},{x:200,y:0}];
  traffic.render(element, layer, path, route, [], {id:'down-link',a}, true);
  assert.equal(path.attrs['traffic-down'], true);
  traffic.render(element, layer, path, route, [], undefined, true);
  assert.equal(path.attrs['traffic-down'], false);
  assert.equal(path.attrs['--traffic-color'], '#687486');
  assert.equal(layer.children.filter(n => n.tag === 'circle').length, 0);
});

test('different lengths retain the same path speed without crowding short links', () => {
  for (const length of [25, 440, 660, 1200]) {
    const layer = element('g'); layer.parentNode = element('g');
    const path = element('path', {d:`M 0 0 L ${length} 0`, length});
    traffic.render(element, layer, path, [{x:0,y:0},{x:length,y:0}], [],
      {a:{up:true, measuredAt:Date.now(), txBps:1, capacityBps:10}}, true);
    const style = layer.trafficPulses.children[0].attrs.style;
    const from = Number(style.match(/--pulse-from:([^p]+)/)[1]);
    const to = Number(style.match(/--pulse-to:([^p]+)/)[1]);
    const period = from - to;
    const duration = Number(style.match(/animation-duration:([^s]+)/)[1]);
    assert.ok(Math.abs(period / duration - 55) < 0.0001);
    assert.ok(Math.abs(period - 1.5 * length / traffic.count(length)) < 0.0001);
    const dash = Number(layer.trafficPulses.children[0].attrs['stroke-dasharray'].split(' ')[0]);
    assert.equal(dash, Math.min(32, length / traffic.count(length) * 0.38));
  }
});


test('wireless classification requires both connected ports, independent of the equipment icon', () => {
  const nodes = [{id:'a', ports:[{id:'w', connector:'wireless'}, {id:'e', connector:'electrical'}]},
    {id:'b', ports:[{id:'w', type:'wireless'}]}];
  const link = {from:'a', to:'b', fromPort:'w', toPort:'w'};
  assert.equal(traffic.isWireless(link, nodes), true);
  assert.equal(traffic.isWireless({...link, fromPort:'e'}, nodes), false);
  assert.equal(traffic.isWireless({...link, toPort:null}, nodes), false);
});
test('wireless uses masked capsules only from A to B and preserves phase on updates', () => {
  const svg = (tag, attributes) => {
    assert.doesNotThrow(() => Object.entries(attributes));
    return element(tag, attributes);
  };
  const layer = element('g'); layer.parentNode = element('g');
  layer.trafficContext = {wireless:true, link:{}};
  const path = element('path', {d:'M 0 0 L 300 100'});
  const route = [{x:0,y:0},{x:300,y:100}];
  const a = {up:true, measuredAt:Date.now(), txBps:90, rxBps:10, capacityBps:100};
  traffic.render(svg, layer, path, route, [], {a}, true);
  const pulses = layer.trafficPulses;
  assert.match(pulses.attrs.mask, /^url/);
  assert.equal(pulses.children.length, 1);
  assert.equal(pulses.children[0].children.length, 3);
  assert.match(pulses.children[0].children[0].attrs.style, /animation-direction:normal/);
  assert.equal(layer.attrs['--wireless-pulse-color'], '#EF4444');
  traffic.render(svg, layer, path, route, [], {a:{...a, txBps:0}}, true);
  assert.equal(layer.trafficPulses, pulses);
  assert.equal(pulses.attrs.visibility, 'visible');
  assert.equal(layer.children.some(child => child.tag === 'text'), false);
  traffic.render(svg, layer, path, route, [], {a}, true, true);
  assert.match(layer.trafficPulses.children[0].children[0].attrs.style, /animation-direction:reverse/);
  traffic.render(svg, layer, path, route, [], {a}, false);
  assert.equal(layer.trafficPulses.attrs.visibility, 'hidden');
});


test('radio links to host groups are wireless in either direction, preserving Ethernet links', () => {
  const nodes = [{id:'radio', icon:'radio', ports:[
    {id:'w', connector:'wireless'}, {id:'e', connector:'electrical'}]},
    {id:'group', groupId:'42', icon:'telecom-site'}, {id:'host', hostId:'17'}];
  const link = {from:'radio', to:'group', fromPort:'w'};
  assert.equal(traffic.isWireless(link, nodes), true);
  assert.equal(traffic.isWireless({from:'group', to:'radio', toPort:'w'}, nodes), true);
  assert.equal(traffic.isWireless({...link, fromPort:null}, nodes), true);
  assert.equal(traffic.isWireless({...link, fromPort:'e'}, nodes), false);
  assert.equal(traffic.isWireless({...link, to:'missing'}, nodes), false);
  assert.equal(traffic.isWireless({...link, to:'host'}, nodes), false);
});


test('links without readings use gray and use the configured source port capacity for utilization', () => {
  for (const wireless of [false, true]) {
    const layer = element('g');
    layer.trafficContext = {wireless, link:{from:'a', fromPort:'w'}};
    const path = element('path', {d:'M 0 0 L 300 0'});
    const route = [{x:0,y:0},{x:300,y:0}];
    const nodes = [{id:'a', ports:[{id:'w', capacity:'1g'}]}];
    traffic.render(element, layer, path, route, nodes, undefined, true);
    assert.equal(path.attrs['--traffic-color'], '#687486');
    for (const [txBps, color] of [[100e6, '#22C55E'], [700e6, '#EAB308'], [900e6, '#EF4444']]) {
      traffic.render(element, layer, path, route, nodes,
        {a:{up:true, measuredAt:Date.now(), txBps}}, true);
      assert.equal(path.attrs['--traffic-color'], color);
      assert.equal(layer.children.some(child => child.tag === 'text'), false);
    }
  }
});

test('Zabbix validity follows each item interval and traffic clock independently', () => {
  const endpoint={measuredAt:10000,validUntil:190000,rxValidUntil:190000,txValidUntil:20000,up:true,rxBps:'0',txBps:'100'};
  assert.equal(traffic.fresh(endpoint,70000),true);
  const data=traffic.state({a:endpoint},70000);
  assert.equal(data.rx,0);assert.equal(data.tx,null);
  assert.equal(traffic.fresh(endpoint,190001),false);
  assert.equal(traffic.fresh({...endpoint,measuredAt:80000},70000),false);
});

test('aggregate PON load does not drive branch color or traffic pulses', () => {
  const layer = element('g');
  layer.trafficContext = {link: {from:'olt', to:'pop', fromPort:'pon1',
    connection:{hostA:'host-olt', interfaceA:'gpon-1'}}};
  const nodes = [{id:'olt', hostId:'host-olt', ports:[{id:'pon1', name:'gpon-1', connector:'pon'}]}];
  const path = element('path', {d:'M 0 0 L 200 0'});
  const route = [{x:0,y:0},{x:200,y:0}];
  const a = {name:'gpon-1', up:true, measuredAt:10000, txBps:90, rxBps:10, capacityBps:100};
  traffic.render(element, layer, path, route, nodes, {a}, true, false, 11000);
  assert.equal(path.attrs['--traffic-color'], '#687486');
  assert.equal(layer.trafficPulses.attrs.visibility, 'hidden');
});

test('solid pulses share geometry and phase, preserve saturation, and stop when down', () => {
  const layer = element('g');
  layer.trafficContext = {link: {appearance: 'solid-pulses'}};
  const d = 'M 0 0 L 0 80 Q 0 100 20 100 L 450 100';
  const path = element('path', {d});
  path.getTotalLength = () => { throw new Error('Fixed spacing needs no path measurement'); };
  const a = {up: true, measuredAt: 10000, txBps: 20, capacityBps: 100};
  const render = (reading, enabled = true) =>
    traffic.render(element, layer, path, [], [], reading, enabled, false, 11000);

  render({a});
  const pulses = layer.trafficPulses;
  assert.equal(path.attrs['--traffic-color'], '#16A34A');
  assert.equal(pulses.attrs.visibility, 'visible');
  assert.deepEqual(pulses.children.map(({attrs}) =>
    [attrs.d, attrs['stroke-dasharray'], attrs['stroke-width'], attrs.stroke, attrs.opacity]), [
    [d, '20 175', 10, '#22C55E', 0.22],
    [d, '20 175', 5, '#22C55E', 0.9],
    [d, '20 175', 2.5, '#F0FFF9', 1],
  ]);

  render({a: {...a, txBps: 85}});
  assert.equal(path.attrs['--traffic-color'], '#F97316');
  assert.equal(layer.trafficPulses, pulses);
  render({a, b: {...a, up: false}});
  assert.equal(path.attrs['traffic-down'], true);
  assert.equal(path.attrs['--traffic-color'], '#EF4444');
  assert.equal(pulses.attrs.visibility, 'hidden');
  render({a}, false);
  assert.equal(path.attrs['traffic-down'], false);
  assert.equal(pulses.attrs.visibility, 'hidden');
});


test('solid pulses mask only shared geometry in visual stacking order', () => {
  const connectors = element('g');
  const layer = (d, points) => {
    const item = element('g');
    item.trafficContext = {link: {appearance:'solid-pulses'}, line: element('path', {d}), route: points.map(([x,y]) => ({x,y}))};
    return item;
  };
  const lower = layer('M 0 0 L 100 0 L 100 100', [[0,0],[100,0],[100,100]]);
  const upper = layer('M 150 0 L 30 0', [[150,0],[30,0]]);
  const crossing = layer('M 60 -20 L 60 20', [[60,-20],[60,20]]);
  const parallel = layer('M 0 2 L 100 2', [[0,2],[100,2]]);
  traffic.maskOverlaps(element, connectors, [lower, upper, crossing, parallel]);
  assert.match(lower.attrs.mask, /^url\(#traffic-overlap-mask-/);
  const masks = connectors.children[0].children;
  assert.equal(masks.length, 1);
  assert.equal(masks[0].children[1].attrs.d, 'M 30 0 L 100 0');
  assert.equal(upper.attrs.mask, undefined);
  assert.equal(crossing.attrs.mask, undefined);
  assert.equal(parallel.attrs.mask, undefined);
});

test('identical rounded paths share a mask even in reverse direction', () => {
  const connectors = element('g');
  const layer = d => {
    const item = element('g');
    item.trafficContext = {link: {appearance:'solid-pulses'}, line: element('path', {d}), route: [{x:0,y:0},{x:100,y:0},{x:100,y:100}]};
    return item;
  };
  const lower = layer('M 0 0 L 82 0 Q 100 0 100 18 L 100 100');
  const upper = layer('M 100 100 L 100 18 Q 100 0 82 0 L 0 0');
  traffic.maskOverlaps(element, connectors, [lower, upper]);
  assert.equal(connectors.children[0].children[0].children[1].attrs.d,
    'M 0 0 L 82 0 M 82 0 Q 100 0 100 18 M 100 18 L 100 100');
  assert.equal(upper.attrs.mask, undefined);
});


test('selection hides its own pulses through periodic updates and restores them on deselection', () => {
  for (const appearance of ['solid-pulses', 'dashed-pulses', undefined]) {
    const layer = element('g');
    const path = element('path', {d:'M 0 0 L 100 0'});
    const route = [{x:0,y:0},{x:100,y:0}];
    const reading = {a:{up:true, measuredAt:Date.now(), txBps:50, capacityBps:100}};
    layer.trafficContext = {link:{appearance}, wireless:appearance === 'dashed-pulses', selected:true};
    traffic.render(element, layer, path, route, [], reading, true);
    assert.equal(layer.trafficPulses.attrs.visibility, 'hidden');
    traffic.render(element, layer, path, route, [], reading, true);
    assert.equal(layer.trafficPulses.attrs.visibility, 'hidden');
    layer.trafficContext.selected = false;
    traffic.render(element, layer, path, route, [], reading, true);
    assert.equal(layer.trafficPulses.attrs.visibility, 'visible');
  }
});

test('a selected simple cable masks shared pulses of every underlying style', () => {
  const connectors = element('g');
  const layers = ['solid-pulses', 'dashed-pulses', undefined].map(appearance => {
    const layer = element('g');
    layer.trafficContext = {link:{appearance}, line:element('path', {d:'M 0 0 L 100 0'}),
      route:[{x:0,y:0},{x:100,y:0}]};
    return layer;
  });
  traffic.maskOverlaps(element, connectors, layers, 'M 30 0 L 70 0');
  assert.equal(connectors.children[0].children.length, 3);
  for (const mask of connectors.children[0].children) {
    assert.equal(mask.children[1].attrs.d, 'M 30 0 L 70 0');
  }
  assert.ok(layers.every(layer => layer.attrs.mask));
});

test('pulse phases are sequential around a shared element and stable across rendering order', () => {
  const links = Array.from({length:6}, (_, index) => ({from:'hub', to:`spoke-${index}`}));
  const phases = traffic.pulsePhases(links);
  assert.deepEqual(links.map(link => phases.get(link)), [0, 1/6, 2/6, 3/6, 4/6, 5/6]);
  assert.deepEqual(traffic.pulsePhases(links), phases);
  const bigHub = Array.from({length:24}, (_, i) => ({from:'big', to:`big-${i}`}));
  const mixed = traffic.pulsePhases([...bigHub, ...links]);
  assert.deepEqual(links.map(link => mixed.get(link)), links.map(link => phases.get(link)));
  const triangle = [{from:'a',to:'b'}, {from:'b',to:'c'}, {from:'c',to:'a'}];
  assert.equal(new Set(traffic.pulsePhases(triangle).values()).size, 3);
  const detached = [{from:'a',to:'b'}, {from:'c',to:'d'}, {from:'e',to:'f'}];
  assert.equal(new Set(traffic.pulsePhases(detached).values()).size, 3);
  assert.equal(traffic.pulsePhases([]).size, 0);
});

test('staggered pulses keep their component paths in phase and retain the speed on refresh', () => {
  const route = [{x:0,y:0},{x:300,y:0}];
  for (const appearance of ['solid-pulses', 'dashed-pulses', undefined]) {
    const layer = element('g');
    const path = element('path', {d:'M 0 0 L 300 0'});
    layer.trafficContext = {link:{appearance}, wireless:appearance === 'dashed-pulses', phase:0.25};
    const reading = {a:{up:true, measuredAt:Date.now(), txBps:50, capacityBps:100}};
    traffic.render(element, layer, path, route, [], reading, true);
    const pulses = layer.trafficPulses;
    const children = appearance === 'dashed-pulses' ? pulses.children[0].children : pulses.children;
    const delays = children.map(node => Number(node.attrs.style.match(/animation-delay:([^s]+)/)[1]));
    const duration = appearance === 'solid-pulses' ? 195 / 60 : (300 / traffic.count(300)) * 1.5 / 55;
    assert.ok(delays.every(delay => Math.abs(delay + 0.75 * duration) < 1e-9));
    for (const child of children) {
      const [dash, gap] = child.attrs['stroke-dasharray'].split(' ').map(Number);
      const actualDuration = Number(child.attrs.style.match(/animation-duration:([^s]+)/)[1]);
      assert.ok(Math.abs(actualDuration - duration) < 1e-9);
      assert.ok(Math.abs((dash + gap) / actualDuration - (appearance === 'solid-pulses' ? 60 : 55)) < 1e-9);
      if (appearance) assert.equal(dash, 20);
    }
    traffic.render(element, layer, path, route, [], reading, true);
    assert.equal(layer.trafficPulses, pulses);
  }
});

test('new SVG animations retain the same clock phase after redraw and navigation', () => {
  const animation = (duration, animationName = 'traffic-flow') => ({
    animationName, startTime: null, effect: {getTiming: () => ({duration})},
  });
  const origin = 1795600000123.5;
  for (const duration of [3250, 300 / traffic.count(300) * 1.5 / 55 * 1000]) {
    const first = animation(duration), redraw = animation(duration), reload = animation(duration);
    const unrelated = animation(duration, 'other');
    traffic.synchronizePulses({getAnimations: () => [first]}, origin);
    traffic.synchronizePulses({getAnimations: () => [redraw, unrelated]}, origin);
    const elapsed = 4237;
    traffic.synchronizePulses({getAnimations: () => [reload]}, origin + elapsed);
    assert.equal(first.startTime, redraw.startTime);
    assert.ok(Math.abs((elapsed - first.startTime) % duration - (-reload.startTime) % duration) < 1e-6);
    assert.equal(unrelated.startTime, null);
  }
});

test('solid and dashed capsules share the same visual layers', () => {
  const route = [{x:0,y:0},{x:300,y:0}];
  const styles = ['solid-pulses', 'dashed-pulses'].map(appearance => {
    const layer = element('g');
    layer.trafficContext = {link:{appearance}, wireless:appearance === 'dashed-pulses'};
    traffic.render(element, layer, element('path', {d:'M 0 0 L 300 0'}), route, [], null, true);
    const paths = appearance === 'solid-pulses' ? layer.trafficPulses.children : layer.trafficPulses.children[0].children;
    return paths.map(({attrs}) => [
      attrs.stroke === 'var(--wireless-pulse-color)' ? layer.attrs['--wireless-pulse-color'] : attrs.stroke,
      attrs['stroke-width'], attrs['stroke-linecap'], attrs.opacity,
      attrs.style.includes('filter:blur(3px);'), Number(attrs['stroke-dasharray'].split(' ')[0]),
    ]);
  });
  assert.deepEqual(styles[0], styles[1]);
});
