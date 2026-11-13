const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const flush = () => new Promise(resolve => setImmediate(resolve));
async function surface({ clipboard = { writeText:async () => {} }, ping = false, pingStart, trafficRows, failLive = false, ixcLogins = [], ixcNames = ['cliente.pppoe'], failNames = false, wirelessClients = [{ '.id':'*9', 'radio-name':'Cliente A', 'mac-address':'AA:BB:CC:DD:EE:FF', interface:'wlan1', 'signal-strength':'-70', 'rx-rate':'13Mbps', 'tx-rate':'115Mbps', 'authentication-type':'wpa2-psk', ap:'false' }] } = {}) {
  class Element {
    constructor() { this.children = []; this.dataset = {}; this.style = {}; this.value = ''; this.textContent = ''; }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
    setAttribute(key, value) { this[key] = value; }
    showModal() { this.open = true; }
    close() { this.open = false; this.onclose?.(); }
    focus() { this.focused = true; }
  }
  const elements = new Proxy({}, { get(object, key) { return object[key] ||= new Element(); } });
  elements['history-period'].value = '1440';
  const tabs = ['interfaces', 'wireless'].map(tab => Object.assign(new Element(), { dataset:{ tab } }));
  const sortButtons = ['mac-address', 'pppoe', 'interface', 'avg-signal', 'avg-signal-to-noise', 'avg-tx-ccq', 'p-throughput', 'distance', 'uptime', 'avg-tx-rate', 'avg-rx-rate', 'quality'].map(key => {
    const button = new Element(), th = new Element(), icon = new Element();
    button.dataset.wirelessSort = key; button.closest = () => th; button.querySelector = () => icon; return button;
  });
  const panels = tabs.map(tab => Object.assign(new Element(), { dataset:{ panel:tab.dataset.tab } }));
  let pingSerial = 0;
  const requests = [], timers = new Map(), intervals = new Map(), events = {};
  let failHistory = false, historyClearCount = 0;
  let serial = 0, sampleAt = Date.now();
  const document = {
    hidden:false, querySelector:() => ({ dataset:{ hostId:'42' } }),
    getElementById:id => elements[id], querySelectorAll:selector => selector === '[data-tab]' ? tabs : selector === '[data-wireless-sort]' ? sortButtons : panels,
    createTextNode:text => Object.assign(new Element(), { textContent:text }), createElement:() => new Element(), createElementNS:() => new Element(), createDocumentFragment:() => new Element(),
    addEventListener:(name, fn) => { events[name] = fn; },
  };
  const context = {
    document, AbortController, Date, navigator:{ clipboard },
    Option:class extends Element { constructor(text, value) { super(); this.textContent = text; this.value = value; } },
    setTimeout:(fn, ms) => { timers.set(++serial, { fn, ms }); return serial; }, clearTimeout:id => timers.delete(id),
    setInterval:fn => { intervals.set(++serial, fn); return serial; }, clearInterval:id => intervals.delete(id),
    fetch:async (url, options) => {
      requests.push({ url, options });
      if (url.includes('/wireless/ping')) {
        if (!ping) return { ok:false, status:503, json:async () => ({ message:'Ping indisponível' }) };
        const starting = options?.method === 'POST';
        if (starting && pingStart) return { ok:true, json:pingStart };
        return { ok:true, json:async () => ({ id:'client-live:' + (starting ? ++pingSerial : pingSerial), ip:'192.0.2.15',
          samples:starting ? [] : [{ at:sampleAt, latency:2.5, loss:0, status:'SUCCESS' }] }) };
      }
      if (url.includes('availability') && failHistory) throw new Error('offline');
      if (url.includes('availability')) return { ok:true, json:async () => ({ from:0, to:60000, bucketMillis:60000, total:{ technical:99.98, operational:99.92, latency:1.46, loss:0.02 }, series:[{ at:1, metrics:{ technical:100, operational:100, latency:2, loss:0 } }] }) };
      if (url.endsWith('/ixc/login-names')) {
        return { ok:!failNames, json:async () => failNames ? { message:'IXC offline' } : Object.fromEntries(
          wirelessClients.filter(client => /^[0-9a-f:.-]{12,17}$/i.test(client['mac-address'] || '')).map(client => [
            client['mac-address'], typeof ixcNames === 'function' ? ixcNames(encodeURIComponent(client['mac-address'])) : ixcNames,
          ])) };
      }
      if (url.includes('/ixc/client?')) return { ok:true, json:async () => ixcLogins };
      if (url.includes('/ixc/login?')) return { ok:true, json:async () => ixcLogins };
      if (failLive) return { ok:false, json:async () => ({ message:'Credenciais inválidas' }) };
      const rows = url.includes('/interfaces') ? [
        { '.id':'*1', name:'ether1', type:'ether', running:'true', mtu:'1500', 'rx-byte':'1000', 'tx-byte':'2000' },
        { '.id':'*2', name:'wlan1', type:'wlan', running:'false', disabled:'true' },
      ] : url.endsWith('/wireless') ? wirelessClients : url.includes('/traffic?') ? (trafficRows ? trafficRows() : [{ 'rx-bits-per-second':'2400', 'tx-bits-per-second':'1200' }]) : [{ identity:'Router', frequency:'5540', channel:'5540 MHz / 20mhz' }];
      return { ok:true, json:async () => ({ measuredAt:sampleAt, rows, warning:'' }) };
    },
    window:{ addEventListener:(name, fn) => { events[name] = fn; }, HostTraffic:require('../../main/resources/static/host-traffic.js'), HostHistoryCharts:{ createRealtime:container => ({ clear() { container.report = null; }, update(report) { container.report = report; } }), clear() { historyClearCount++; }, update(report) { elements['availability-chart'].report = report; } } },
  };
  vm.runInNewContext(require('node:fs').readFileSync('src/main/resources/static/host-menu.js', 'utf8'), context);
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/availability-view.js', 'utf8'), context);
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/host-signal.js', 'utf8'), context);
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/host.js', 'utf8'), context);
  await flush();
  tabs[0].onclick(); await flush();
  return { elements, tabs, panels, requests, timers, events, document, sortButtons, intervals, advance:ms => { sampleAt += ms; }, historyClearCount:() => historyClearCount, failHistory:() => { failHistory = true; } };
}
test('management failure preserves independent historical charts and displays its reason', async () => {
  const ui = await surface({ failLive:true });
  assert.equal(ui.elements['availability-chart'].report.series.length, 1);
  assert.equal(ui.elements['live-error'].hidden, false);
  assert.equal(ui.elements['live-error'].textContent, 'Credenciais inválidas');
});
test('switching tabs only polls the active section and clears earlier timers', async () => {
  const ui = await surface();
  ui.tabs[1].onclick(); await flush();
  assert.ok(ui.requests.some(r => r.url.endsWith('/api/ap/42/wireless')));
  assert.equal(ui.panels[0].hidden, true);
  assert.equal(ui.panels[1].hidden, false);
  assert.equal(ui.timers.size, 1);
});
test('hiding the page stops all future live requests', async () => {
  const ui = await surface();
  ui.document.hidden = true; ui.events.visibilitychange();
  assert.equal(ui.timers.size, 0);
});

test('wireless modal keeps collecting in the background until closed', async () => {
  const clients = [{ 'mac-address':'AA', interface:'wlan1', bytes:'1000,2000' }];
  const ui = await surface({ wirelessClients:clients });
  ui.tabs[1].onclick(); await flush();
  ui.elements['wireless-clients'].children[0].children[0].children[0].children[0].onclick();
  await flush();
  const timer = ui.timers.values().next().value;
  const before = ui.requests.length;
  ui.document.hidden = true; ui.events.visibilitychange();
  assert.equal(ui.timers.values().next().value, timer);
  ui.advance(3000); clients[0].bytes = '751000,377000';
  for (const [id, entry] of ui.timers) if (entry === timer) ui.timers.delete(id);
  await timer.fn();
  assert.ok(ui.requests.length > before);
  assert.equal(ui.elements['wireless-download'].textContent, '2.000 kbps');
  ui.document.hidden = false; ui.events.visibilitychange();
  assert.equal(ui.elements['wireless-download'].textContent, '2.000 kbps');
  ui.document.hidden = true; ui.events.visibilitychange();
  ui.elements['wireless-modal'].close(); await flush();
  assert.equal(ui.timers.size, 0);
});

test('interface modal keeps traffic polling active in the background', async () => {
  const ui = await surface();
  ui.elements['interface-rows'].children[0].children[0].onclick(); await flush();
  const timer = ui.timers.values().next().value;
  const before = ui.requests.filter(request => request.url.includes('/traffic?')).length;
  ui.document.hidden = true; ui.events.visibilitychange();
  assert.equal(ui.timers.values().next().value, timer);
  for (const [id, entry] of ui.timers) if (entry === timer) ui.timers.delete(id);
  await timer.fn();
  assert.equal(ui.requests.filter(request => request.url.includes('/traffic?')).length, before + 1);
  ui.elements['interface-modal'].close(); await flush();
  assert.equal(ui.timers.size, 0);
});

test('overview stays populated above the wireless tab', async () => {
  const ui = await surface();
  ui.tabs[1].onclick(); await flush();
  assert.ok(ui.requests.some(request => request.url.endsWith('/api/ap/42/overview')));
  const details = ui.elements['resource-details'].children[0].children;
  const frequency = details.find(field => field.children[0].textContent === 'Frequência / Canal');
  assert.equal(frequency.children[1].textContent, '5540 MHz / 20mhz');
  assert.equal(details.some(field => field.children[0].textContent === 'Canal'), false);
  assert.equal(ui.timers.size, 1);
});

test('interfaces render one row per interface and opening a row starts live traffic in a modal', async () => {
  const ui = await surface();
  const rows = ui.elements['interface-rows'].children[0].children;
  assert.equal(rows.length, 2);
  assert.equal(rows[0].children[0].textContent, 'ether1');
  assert.equal(rows[1].children[2].textContent, 'Desabilitada');
  assert.equal(ui.requests.some(request => request.url.includes('/traffic?')), false);
  rows[0].onclick(); await flush();
  assert.equal(ui.elements['interface-modal'].open, true);
  assert.equal(ui.elements['interface-modal-title'].textContent, 'ether1');
  assert.ok(ui.requests.at(-1).url.endsWith('/traffic?selectedInterface=*1'));
  assert.ok(ui.elements['traffic-chart'].children.length);
});

test('closing the interface modal stops traffic polling while the interface list keeps updating', async () => {
  const ui = await surface();
  ui.elements['interface-rows'].children[0].children[0].onclick(); await flush();
  const count = () => ui.requests.filter(request => request.url.includes('/traffic?')).length;
  assert.equal(count(), 1);
  ui.elements['interface-modal-close'].onclick(); await flush();
  assert.equal(ui.elements['interface-modal'].open, false);
  assert.equal(count(), 1);
  assert.equal(ui.timers.size, 1);
});

test('keyboard activation opens the selected interface', async () => {
  const ui = await surface();
  let prevented = false;
  ui.elements['interface-rows'].children[0].children[1].onkeydown({ key:'Enter', preventDefault() { prevented = true; } });
  await flush();
  assert.equal(prevented, true);
  assert.equal(ui.elements['interface-modal-title'].textContent, 'wlan1');
  assert.ok(ui.requests.at(-1).url.endsWith('/traffic?selectedInterface=*2'));
});

test('host header displays the four historical metrics and the selected date range', async () => {
  const ui = await surface();
  assert.equal(ui.elements.operational.textContent, '99,92%');
  assert.equal(ui.elements.technical.textContent, '99,98%');
  assert.equal(ui.elements.loss.textContent, '0,02%');
  assert.equal(ui.elements.latency.textContent, '1,46 ms');
  assert.equal(ui.elements['history-range'].textContent,
    [0, 60000].map(at => new Date(at).toLocaleString('pt-BR')).join(' — '));
  ui.elements['history-period'].value = '15';
  await ui.elements['history-period'].onchange();
  assert.ok(ui.requests.some(request => request.url.endsWith('/availability?minutes=15')));
});

test('wireless client opens compact diagnosis in a modal', async () => {
  const ui = await surface();
  ui.tabs[1].onclick(); await flush();
  ui.elements['wireless-clients'].children[0].children[0].children[0].children[0].onclick();
  assert.equal(ui.elements['wireless-modal'].open, true);
  assert.equal(ui.elements['wireless-modal-title'].textContent, 'Cliente A');
  assert.equal(ui.elements['wireless-metrics'].children[0].children.length, 4);
  ui.elements['wireless-modal-close'].onclick();
  assert.equal(ui.elements['wireless-modal'].open, false);
});

test('wireless modal preserves last details when the client disconnects', async () => {
  const clients = [{ 'mac-address':'AA:BB', interface:'wlan1' }];
  const ui = await surface({ wirelessClients:clients });
  ui.tabs[1].onclick(); await flush();
  ui.elements['wireless-clients'].children[0].children[0].children[0].children[0].onclick();
  clients.length = 0;
  await ui.timers.values().next().value.fn();
  assert.equal(ui.elements['wireless-modal'].open, true);
  assert.match(ui.elements['wireless-live-state'].textContent, /não consta mais/);
});

test('wireless table shows PPPoE login between MAC and interface', async () => {
  const ui = await surface();
  ui.tabs[1].onclick(); await flush();
  const row = ui.elements['wireless-clients'].children[0].children[0];
  assert.equal(row.children[1].textContent, 'cliente.pppoe');
  assert.equal(row.children[2].textContent, 'wlan1');

});

test('wireless table rounds signal, SNR and CCQ to integers without inventing absent readings', async () => {
  const ui = await surface({ wirelessClients:[
    { 'mac-address':'AA', signal:'-71.95', 'avg-signal':'-71.95', 'avg-signal-to-noise':'41.05', 'avg-tx-ccq':'71.43' },
    { 'mac-address':'BB', signal:'-63', 'avg-signal':'-63', 'avg-tx-ccq':'0' },
    { 'mac-address':'CC' },
  ] });
  ui.tabs[1].onclick(); await flush();
  const rows = ui.elements['wireless-clients'].children[0].children;
  assert.equal(rows[0].children[3].children[1].textContent, '-72 dBm');
  assert.equal(rows[0].children[4].textContent, '41 dB');
  assert.equal(rows[0].children[5].textContent, '71%');
  assert.equal(rows[1].children[3].children[1].textContent, '-63 dBm');
  assert.equal(rows[1].children[5].textContent, '0%');
  assert.equal(rows[2].children[3].children[1].textContent, '—');
  assert.equal(rows[2].children[5].textContent, '—');
});

test('wireless sorts numeric signal, CCQ, durations and rates in both directions, preserving live updates', async () => {
  const clients = [
    { 'mac-address':'BB', 'radio-name':'Rádio 10', 'avg-signal':'-71', 'avg-tx-ccq':'90', uptime:'1d', 'avg-tx-rate':'6', 'avg-rx-rate':'0.5' },
    { 'mac-address':'AA', 'radio-name':'Rádio 2', 'avg-signal':'-63', 'avg-tx-ccq':'100', uptime:'3h', 'avg-tx-rate':'130', 'avg-rx-rate':'2' },
    { 'mac-address':'CC' },
  ];
  const ui = await surface({ wirelessClients:clients });
  ui.tabs[1].onclick(); await flush();
  const names = () => ui.elements['wireless-clients'].children[0].children.map(row => row.children[0].children[0].textContent);
  const sort = key => ui.sortButtons.find(button => button.dataset.wirelessSort === key).onclick();
  sort('avg-signal'); assert.deepEqual(names(), ['BB','AA','CC']);
  sort('avg-signal'); assert.deepEqual(names(), ['AA','BB','CC']);
  assert.equal(ui.sortButtons[3].closest().getAttribute?.('aria-sort') ?? ui.sortButtons[3].closest()['aria-sort'], 'descending');
  sort('avg-tx-ccq'); assert.deepEqual(names(), ['BB','AA','CC']);
  sort('uptime'); assert.deepEqual(names(), ['AA','BB','CC']);
  sort('avg-tx-rate'); assert.deepEqual(names(), ['BB','AA','CC']);
  sort('avg-rx-rate'); assert.deepEqual(names(), ['BB','AA','CC']);
  sort('pppoe'); assert.deepEqual(names(), ['BB','AA','CC']);
  await ui.timers.values().next().value.fn();
  assert.deepEqual(names(), ['BB','AA','CC']);
});

test('periodic TraceOne refresh leaves equipment data and open modals untouched without clearing charts', async () => {
  const ui = await surface();
  ui.elements['interface-rows'].children[0].children[0].onclick(); await flush();
  const row = ui.elements['interface-rows'].children[0];
  const detail = ui.elements['interface-details'].children[0];
  const clears = ui.historyClearCount(), before = ui.requests.length;
  ui.intervals.values().next().value(); await flush();
  assert.deepEqual(ui.requests.slice(before).map(request => request.url), ['/api/hosts/42/availability?minutes=1440']);
  assert.equal(ui.historyClearCount(), clears);
  assert.equal(ui.elements['interface-rows'].children[0], row);
  assert.equal(ui.elements['interface-details'].children[0], detail);
  assert.equal(ui.elements['interface-modal'].open, true);
});

test('background indicator failure preserves the last metrics and charts', async () => {
  const ui = await surface();
  const report = ui.elements['availability-chart'].report, clears = ui.historyClearCount();
  ui.failHistory();
  ui.intervals.values().next().value(); await flush();
  assert.equal(ui.elements.operational.textContent, '99,92%');
  assert.equal(ui.elements['availability-chart'].report, report);
  assert.equal(ui.historyClearCount(), clears);
  assert.match(ui.elements['history-state'].textContent, /última consulta/);
});

test('wireless shows and numerically sorts throughput and distance, keeping absent values unavailable', async () => {
  const ui = await surface({ wirelessClients:[
    { 'mac-address':'AA', 'p-throughput':'10000', distance:'12' },
    { 'mac-address':'BB', 'p-throughput':'900', distance:'2' },
    { 'mac-address':'CC' },
  ] });
  ui.tabs[1].onclick(); await flush();
  const rows = () => ui.elements['wireless-clients'].children[0].children;
  assert.equal(rows()[0].children[6].textContent, '10.000');
  assert.equal(rows()[0].children[7].textContent, '12');
  assert.equal(rows()[2].children[6].textContent, '—');
  assert.equal(rows()[2].children[7].textContent, '—');
  for (const key of ['p-throughput', 'distance']) {
    ui.sortButtons.find(button => button.dataset.wirelessSort === key).onclick();
    assert.deepEqual(rows().map(row => row.children[0].children[0].textContent), ['BB','AA','CC']);
  }
});

test('wireless modal shows actual bandwidth and preserves quality chart when sorting', async () => {
  const clients = [{ '.id':'*1', 'mac-address':'AA', interface:'wlan1', bytes:'1000,2000' }];
  const ui = await surface({ wirelessClients:clients });
  ui.tabs[1].onclick(); await flush();
  ui.elements['wireless-clients'].children[0].children[0].children[0].children[0].onclick();
  assert.equal(ui.elements['wireless-download'].textContent, 'Aguardando…');
  ui.advance(3000); clients[0].bytes = '751000,377000';
  await ui.timers.values().next().value.fn();
  assert.equal(ui.elements['wireless-download'].textContent, '2.000 kbps');
  assert.equal(ui.elements['wireless-upload'].textContent, '1.000 kbps');
  const plot = ui.elements['wireless-quality-chart'].children[0];
  const trafficPlot = ui.elements['wireless-traffic-chart'].children[0];
  assert.ok(trafficPlot, 'measured traffic has its own line chart');
  assert.match(trafficPlot.children[1].d, /M/);
  assert.equal(trafficPlot.children[3].children[0].fill, 'transparent');
  ui.sortButtons[0].onclick();
  assert.equal(ui.elements['wireless-traffic-chart'].children[0], trafficPlot);
  assert.equal(ui.elements['wireless-quality-chart'].children[0], plot);
  clients.length = 0; ui.advance(3000);
  await ui.timers.values().next().value.fn();
  assert.equal(ui.elements['wireless-download'].textContent, 'Indisponível');
  assert.equal(ui.elements['wireless-quality-chart'].children[0], plot);
});

test('wireless traffic fills absent values and missed collection intervals with zero', async () => {
  const clients = [{ '.id':'*1', 'mac-address':'AA', interface:'wlan1',
    'tx-bits-per-second':'15000' }];
  const ui = await surface({ wirelessClients:clients });
  ui.tabs[1].onclick(); await flush();
  ui.elements['wireless-clients'].children[0].children[0].children[0].children[0].onclick();
  const plot = ui.elements['wireless-traffic-chart'].children[0];
  assert.match(plot.children[2].d, /^M100,185 /);
  ui.advance(5000);
  delete clients[0]['tx-bits-per-second'];
  await ui.timers.values().next().value.fn();
  for (const line of plot.children.slice(1, 3)) {
    assert.equal((line.d.match(/M/g) || []).length, 1, 'line stays connected');
    assert.equal((line.d.match(/C/g) || []).length, 5, 'missing seconds get points');
    assert.match(line.d, /740,185 $/, 'missing latest value is zero');
  }
  const titles = plot.children[3].children.map(dot => dot.children[0].textContent);
  assert.ok(titles.some(title => title.includes('Download: 0 kbps · Upload: 0 kbps')));
});

test('wireless modal uses the same directional rates as the quality diagnosis', async () => {
  const client = { 'mac-address':'AA', interface:'wlan1', 'connection-status':'connected',
    'tx-traffic-bps':'15000', 'rx-traffic-bps':'5000000', quality:'Normal' };
  const ui = await surface({ wirelessClients:[client] });
  ui.tabs[1].onclick(); await flush();
  const cell = ui.elements['wireless-clients'].children[0].children[0].children[0];
  assert.equal(cell.children.length, 1);
  assert.equal(cell.children[0].textContent, 'AA');
  cell.children[0].onclick();
  assert.equal(ui.elements['wireless-download'].textContent, '15 kbps');
  assert.equal(ui.elements['wireless-upload'].textContent, '5 Mbps');
});

test('wireless modal omits the removed client information section', () => {
  const html = fs.readFileSync('src/main/resources/templates/host.html', 'utf8');
  assert.doesNotMatch(html, /id="(?:wireless-details|ixc-query|ixc-details)"/);
  assert.doesNotMatch(html, /Informações do cliente/);
});

test('PPPoE names refresh separately from live polling and never fetch passwords automatically', async () => {
  const ui = await surface({ ixcNames:['cliente.1', 'cliente.2'] });
  ui.tabs[1].onclick(); await flush();
  const row = ui.elements['wireless-clients'].children[0].children[0];
  assert.equal(row.children[1].textContent, 'cliente.1 / cliente.2');
  await ui.timers.values().next().value.fn(); await flush();
  assert.equal(ui.requests.filter(r => r.url.endsWith('/ixc/login-names')).length, 1);
  assert.equal(ui.requests.filter(r => r.url.includes('/ixc/login?')).length, 0);
});
test('missing and unavailable IXC logins keep the wireless table available', async () => {
  const missing = await surface({ ixcNames:[] });
  missing.tabs[1].onclick(); await flush();
  assert.equal(missing.elements['wireless-clients'].children[0].children[0].children[1].textContent, 'Sem login vinculado');
  const failed = await surface({ failNames:true });
  failed.tabs[1].onclick(); await flush();
  assert.equal(failed.elements['wireless-clients'].children[0].children[0].children[1].textContent, 'IXC indisponível');
  assert.match(failed.elements['wireless-ixc-state'].textContent, /IXC offline/);
});

test('PPPoE column sorts by linked login rather than radio name', async () => {
  const ui = await surface({
    wirelessClients:[
      { 'mac-address':'AA:BB:CC:DD:EE:01', 'radio-name':'Radio A' },
      { 'mac-address':'AA:BB:CC:DD:EE:02', 'radio-name':'Radio Z' },
    ],
    ixcNames:url => url.endsWith('01') ? ['zulu.pppoe'] : ['alfa.pppoe'],
  });
  ui.tabs[1].onclick(); await flush();
  const sort = ui.sortButtons.find(button => button.dataset.wirelessSort === 'pppoe');
  const names = () => ui.elements['wireless-clients'].children[0].children.map(row => row.children[1].textContent);
  sort.onclick(); assert.deepEqual(names(), ['alfa.pppoe','zulu.pppoe']);
  sort.onclick(); assert.deepEqual(names(), ['zulu.pppoe','alfa.pppoe']);
});

test('batch lookup includes all clients and a missing MAC does not affect the others', async () => {
  const ui = await surface({
    wirelessClients:[
      { 'mac-address':'AA:BB:CC:DD:EE:01' },
      { 'mac-address':'AA:BB:CC:DD:EE:02' },
      { 'mac-address':'AA:BB:CC:DD:EE:03' },
    ],
    ixcNames:url => url.endsWith('02') ? [] : [url.endsWith('01') ? 'cliente.1' : 'cliente.3'],
  });
  ui.tabs[1].onclick(); await flush();
  const values = () => ui.elements['wireless-clients'].children[0].children.map(row => row.children[1].textContent);
  assert.deepEqual(values(), ['cliente.1','Sem login vinculado','cliente.3']);
  assert.equal(ui.requests.filter(r => r.url.endsWith('/ixc/login-names')).length, 1);
  await ui.timers.values().next().value.fn(); await flush();
  assert.equal(ui.requests.filter(r => r.url.endsWith('/ixc/login-names')).length, 1);
});

test('wireless preserves original diagnosis, instant alerts, disconnected values and live severity order', async () => {
  const clients = [
    { 'mac-address':'AA', interface:'wlan1', signal:'-61', 'avg-signal':'-61', quality:'Normal', 'quality-severity':'0', 'quality-reason':'Sinal médio: -61 dBm — Normal', 'critical-alert':'SNR crítico agora: 10' },
    { 'mac-address':'BB', interface:'wlan1', quality:'Ruim', 'quality-severity':'2', 'connection-status':'disconnected' },
  ];
  const ui = await surface({ wirelessClients:clients }); ui.tabs[1].onclick(); await flush();
  const rows = () => ui.elements['wireless-clients'].children[0].children;
  assert.equal(rows()[0].dataset.clientMac, 'BB');
  assert.match(rows()[0]['aria-label'], /Desconectado/);
  assert.equal(rows()[1].dataset.quality, '0');
  assert.equal(rows()[1].children.length, 11);
  assert.match(rows()[1]['aria-label'], /Crítico agora/);
  assert.equal(rows()[1].title, undefined); // Native tooltip is replaced by the diagnosis card.
  clients[0]['quality-severity'] = '3';
  await ui.timers.values().next().value.fn();
  assert.equal(rows()[0].dataset.clientMac, 'AA');
});

test('all clients and interfaces remain visible across live updates', async () => {
  const ui = await surface({ wirelessClients:[
    { 'mac-address':'AA', interface:'wlan1', signal:'-50', 'avg-signal':'-51', quality:'Crítico', 'quality-severity':'3', 'avg-tx-ccq':'50' },
    { 'mac-address':'BB', interface:'wlan2', quality:'Normal', 'quality-severity':'0', 'avg-tx-ccq':'90' },
    { 'mac-address':'CC', interface:'wlan1', signal:'-75' },
  ] });
  ui.tabs[1].onclick(); await flush();
  assert.equal(ui.elements['connected-clients'].textContent, 3);
  assert.equal(ui.elements['weak-clients'].textContent, 1);
  const rows = () => ui.elements['wireless-clients'].children[0].children;
  assert.match(rows()[0]['aria-label'], /Crítico/);
  await ui.timers.values().next().value.fn();
  assert.equal(rows().length, 3);
  ui.tabs[0].onclick(); await flush();
  assert.equal(ui.elements['interface-rows'].children[0].children.length, 2);
});

test('wireless uses independent metric colors and includes unrated clients in the donut', async () => {
  const ui = await surface({ wirelessClients:[
    { 'mac-address':'AA', interface:'wlan1', 'quality-severity':'2', 'signal-severity':'0',
      'avg-signal':'-62', 'signal-to-noise-severity':'2', 'avg-signal-to-noise':'20' },
    { 'mac-address':'BB', interface:'wlan1' },
  ] });
  ui.tabs[1].onclick(); await flush();
  const rows = () => ui.elements['wireless-clients'].children[0].children;
  assert.equal(rows()[0].dataset.quality, '2');
  assert.equal(rows()[0].children[3].dataset.level, '0');
  assert.equal(rows()[0].children[4].dataset.level, '2');
  assert.match(ui.elements['signal-distribution']['aria-label'], /Indisponível 1 \(50%\)/);
  assert.equal(rows()[1].dataset.clientMac, 'BB');
});

test('wireless summary filters quality and total restores worst-first order after manual sorting', async () => {
  const clients = [0, 1, 2, 3, -1].map((severity, index) => ({
    'mac-address':String.fromCharCode(65 + index), interface:'wlan1',
    'quality-severity':String(severity),
  }));
  const ui = await surface({ wirelessClients:clients });
  ui.tabs[1].onclick(); await flush();
  const rows = () => ui.elements['wireless-clients'].children[0].children;
  const badges = () => ui.elements['wireless-summary'].children;
  const qualities = () => rows().map(row => row.dataset.quality);
  badges()[1].onclick();
  assert.deepEqual(qualities(), ['3']);
  assert.equal(badges()[1]['aria-pressed'], 'true');
  badges()[2].onclick();
  assert.deepEqual(qualities(), ['2']);
  badges()[3].onclick();
  assert.deepEqual(qualities(), ['1']);
  await ui.timers.values().next().value.fn();
  assert.deepEqual(qualities(), ['1']);
  assert.equal(badges()[3]['aria-pressed'], 'true');
  clients[1]['quality-severity'] = '0';
  await ui.timers.values().next().value.fn();
  assert.equal(rows().length, 0);
  ui.sortButtons.find(button => button.dataset.wirelessSort === 'mac-address').onclick();
  badges()[0].onclick();
  assert.deepEqual(qualities(), ['3', '2', '0', '0', '-1']);
  assert.equal(badges()[0]['aria-pressed'], 'true');
  assert.equal(ui.sortButtons[0].closest('th')['aria-sort'], 'none');
});

test('client IPv4 ping starts a fresh session on opening and stops on modal close', async () => {
  const ui = await surface({ ping:true });
  ui.tabs[1].onclick(); await flush();
  const open = () => ui.elements['wireless-clients'].children[0].children[0].children[0].children[0].onclick();
  open(); await flush();
  assert.equal(ui.requests.filter(r => r.options?.method === 'POST' && r.url.includes('/wireless/ping?mac=')).length, 1);
  assert.equal(ui.elements['wireless-ping-chart'].report.series[0].metrics.latency, 2.5);
  ui.elements['wireless-modal'].close(); await flush();
  assert.ok(ui.requests.some(r => r.options?.method === 'DELETE' && r.url.endsWith('client-live%3A1')));
  assert.equal(ui.elements['wireless-ping-chart'].report, null);
  const historyRequests = ui.requests.filter(r => r.url.includes('/availability')).length;
  open(); await flush();
  assert.ok(ui.requests.some(r => r.options?.method === 'GET' || (!r.options?.method && r.url.endsWith('client-live%3A2'))));
  assert.equal(ui.requests.filter(r => r.url.includes('/availability')).length, historyRequests);
});

test('closing while IXC lookup is pending removes the late-created ping session', async () => {
  let finish;
  const ui = await surface({ ping:true, pingStart:() => new Promise(resolve => { finish = resolve; }) });
  ui.tabs[1].onclick(); await flush();
  ui.elements['wireless-clients'].children[0].children[0].children[0].children[0].onclick();
  await flush();
  ui.elements['wireless-modal'].close();
  finish({ id:'client-live:late', ip:'192.0.2.15', samples:[] }); await flush();
  assert.ok(ui.requests.some(r => r.options?.method === 'DELETE' && r.url.endsWith('client-live%3Alate')));
  assert.equal(ui.elements['wireless-ping-chart'].report, null);
});

test('IXC header shows PPPoE plan and safe router link, copying password only on click', async () => {
  const copies = [];
  const ui = await surface({ clipboard:{ writeText:async value => { copies.push(value); } },
    ixcLogins:[{ pppoe:'cliente.100', ip:'192.0.2.15', connectionPort:'8080', equipmentPassword:'router-secret',
      plan:{ download:'100M', upload:'50M' } }] });
  ui.tabs[1].onclick(); await flush();
  ui.elements['wireless-clients'].children[0].children[0].children[0].children[0].onclick(); await flush();
  assert.equal(ui.elements['wireless-modal-title'].textContent, 'cliente.100');
  const [plan, link, status] = ui.elements['wireless-modal-subtitle'].children;
  assert.match(plan.textContent, /100 Mbps.*50 Mbps/);
  assert.equal(link.href, 'http://192.0.2.15:8080');
  assert.equal(link.target, '_blank');
  assert.deepEqual(copies, []);
  assert.ok(!JSON.stringify(ui.elements['wireless-modal-subtitle']).includes('router-secret'));
  link.onclick(); await flush();
  assert.deepEqual(copies, ['router-secret']);
  assert.match(status.textContent, /copiada/);
  ui.elements['wireless-modal'].close();
  assert.equal(ui.elements['wireless-modal-subtitle'].children.length, 0);
});

test('without IXC record the header uses radio name and MAC only', async () => {
  const ui = await surface(); ui.tabs[1].onclick(); await flush();
  ui.elements['wireless-clients'].children[0].children[0].children[0].children[0].onclick(); await flush();
  assert.equal(ui.elements['wireless-modal-title'].textContent, 'Cliente A');
  assert.equal(ui.elements['wireless-modal-subtitle'].textContent, 'AA:BB:CC:DD:EE:FF');
});
test('CCQ badge uses 80 percent threshold and shows traffic assessment below it', async () => {
  for (const [fields, value, status] of [
    [{ 'avg-tx-ccq':'92.88', 'tx-ccq-severity':'-1' }, '92,88%', 'Normal'],
    [{ 'avg-tx-ccq':'92.88' }, '92,88%', 'Normal'],
    [{ 'avg-tx-ccq':'92.88', 'tx-ccq-severity':'0' }, '92,88%', 'Normal'],
    [{ 'avg-tx-ccq':'85.3', 'tx-ccq-severity':'2' }, '85,30%', 'Normal'],
    [{ 'avg-tx-ccq':'80' }, '80,00%', 'Normal'],
    [{ 'avg-tx-ccq':'79.99', 'tx-ccq-severity':'1' }, '79,99%', 'Avaliado sob tráfego'],
    [{ 'avg-tx-ccq':'75', 'tx-ccq-severity':'-1' }, '75,00%', 'Não avaliado sob tráfego'],
    [{ 'avg-tx-ccq':'0' }, '0,00%', 'Não avaliado sob tráfego'],
    [{}, '—', 'Indisponível'],
  ]) {
    const ui = await surface({ wirelessClients:[{ 'mac-address':'AA', interface:'wlan1', ...fields }] });
    ui.tabs[1].onclick(); await flush();
    ui.elements['wireless-clients'].children[0].children[0].children[0].children[0].onclick();
    const card = ui.elements['wireless-metrics'].children[0].children[2];
    assert.equal(card.children[1].textContent, value);
    assert.equal(card.children[2].textContent, status);
    if (status === 'Normal') assert.equal(Number(card.children[2].dataset.level), 0);
  }
});


test('interface monitor limits details to four cards and reflects operational state', async () => {
  const ui = await surface();
  const rows = ui.elements['interface-rows'].children[0].children;
  rows[0].onclick(); await flush();
  const cards = ui.elements['interface-details'].children[0].children;
  assert.deepEqual(cards.map(card => card.children[1].children[0].textContent), ['Nome', 'Tipo', 'MAC', 'MTU']);
  assert.equal(ui.elements['interface-status'].textContent, 'Ativa');
  assert.equal(ui.elements['interface-download'].textContent, '2,4 kbps');
  assert.equal(ui.elements['interface-upload'].textContent, '1,2 kbps');
  rows[1].onclick(); await flush();
  assert.equal(ui.elements['interface-status'].textContent, 'Desabilitada');
});

test('invalid interface readings preserve valid time and chart instance until recovery', async () => {
  let rows = [{ 'rx-bits-per-second':'1000000000', 'tx-bits-per-second':'500' }];
  const ui = await surface({ trafficRows:() => rows });
  ui.elements['interface-rows'].children[0].children[0].onclick(); await flush();
  const time = ui.elements['interface-updated-at'].textContent;
  const svg = ui.elements['traffic-chart'].children[0];
  assert.equal(ui.elements['interface-download'].textContent, '1 Gbps');
  assert.equal(ui.elements['interface-upload'].textContent, '500 bps');
  const tick = async () => {
    const [id, timer] = [...ui.timers].find(([, timer]) => timer.ms === 1000);
    ui.timers.delete(id); ui.advance(1000); await timer.fn();
  };
  rows = [{ 'rx-bits-per-second':'', 'tx-bits-per-second':'-1' }];
  await tick();
  assert.equal(ui.elements['interface-updated-at'].textContent, time);
  assert.match(ui.elements['interface-live-state'].textContent, /desatualizados/);
  rows = [{ 'rx-bits-per-second':'0', 'tx-bits-per-second':'1000000' }];
  await tick();
  assert.equal(ui.elements['traffic-chart'].children[0], svg);
  assert.equal(ui.elements['interface-download'].textContent, '0 bps');
  assert.equal(ui.elements['interface-upload'].textContent, '1 Mbps');
  assert.equal(ui.elements['interface-live-state'].textContent, '');
});


test('interface graph uses Wi-Fi chart geometry and smooth two-pixel lines', async () => {
  const ui = await surface();
  ui.elements['interface-rows'].children[0].children[0].onclick(); await flush();
  const svg = ui.elements['traffic-chart'].children[0];
  const [id, timer] = [...ui.timers].find(([, entry]) => entry.ms === 1000);
  ui.timers.delete(id); ui.advance(1000); await timer.fn();
  assert.equal(svg.viewBox, '0 0 760 220');
  const lines = svg.children.filter(node => node['stroke-width'] === 2);
  assert.equal(lines.length, 2);
  assert.equal(lines[0].stroke, '#22d3ee');
  assert.equal(lines[1].stroke, '#4ade80');
  assert.ok(lines.every(line => line.d.includes('C')));
});
