const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const flush = () => new Promise(resolve => setImmediate(resolve));
async function surface({ failLive = false, ixcLogins = [], ixcNames = ['cliente.pppoe'], failNames = false, wirelessClients = [{ '.id':'*9', 'radio-name':'Cliente A', 'mac-address':'AA:BB:CC:DD:EE:FF', interface:'wlan1', 'signal-strength':'-70', 'rx-rate':'13Mbps', 'tx-rate':'115Mbps', 'authentication-type':'wpa2-psk', ap:'false' }] } = {}) {
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
  const sortButtons = ['mac-address', 'pppoe', 'interface', 'avg-signal', 'avg-signal-to-noise', 'avg-tx-ccq', 'avg-rx-ccq', 'p-throughput', 'distance', 'uptime', 'avg-tx-rate', 'avg-rx-rate', 'quality'].map(key => {
    const button = new Element(), th = new Element(), icon = new Element();
    button.dataset.wirelessSort = key; button.closest = () => th; button.querySelector = () => icon; return button;
  });
  const panels = tabs.map(tab => Object.assign(new Element(), { dataset:{ panel:tab.dataset.tab } }));
  const requests = [], timers = new Map(), intervals = new Map(), events = {};
  let failHistory = false, historyClearCount = 0;
  let serial = 0, sampleAt = Date.now();
  const document = {
    hidden:false, querySelector:() => ({ dataset:{ hostId:'42' } }),
    getElementById:id => elements[id], querySelectorAll:selector => selector === '[data-tab]' ? tabs : selector === '[data-wireless-sort]' ? sortButtons : panels,
    createElement:() => new Element(), createElementNS:() => new Element(), createDocumentFragment:() => new Element(),
    addEventListener:(name, fn) => { events[name] = fn; },
  };
  const context = {
    document, AbortController, Date,
    Option:class extends Element { constructor(text, value) { super(); this.textContent = text; this.value = value; } },
    setTimeout:(fn, ms) => { timers.set(++serial, { fn, ms }); return serial; }, clearTimeout:id => timers.delete(id),
    setInterval:fn => { intervals.set(++serial, fn); return serial; }, clearInterval:id => intervals.delete(id),
    fetch:async (url, options) => {
      requests.push({ url, options });
      if (url.includes('availability') && failHistory) throw new Error('offline');
      if (url.includes('availability')) return { ok:true, json:async () => ({ from:0, to:60000, bucketMillis:60000, total:{ technical:99.98, operational:99.92, latency:1.46, loss:0.02 }, series:[{ at:1, metrics:{ technical:100, operational:100, latency:2, loss:0 } }] }) };
      if (url.endsWith('/ixc/login-names')) {
        return { ok:!failNames, json:async () => failNames ? { message:'IXC offline' } : Object.fromEntries(
          wirelessClients.filter(client => /^[0-9a-f:.-]{12,17}$/i.test(client['mac-address'] || '')).map(client => [
            client['mac-address'], typeof ixcNames === 'function' ? ixcNames(encodeURIComponent(client['mac-address'])) : ixcNames,
          ])) };
      }
      if (url.includes('/ixc/login?')) return { ok:true, json:async () => ixcLogins };
      if (failLive) return { ok:false, json:async () => ({ message:'Credenciais inválidas' }) };
      const rows = url.includes('/interfaces') ? [
        { '.id':'*1', name:'ether1', type:'ether', running:'true', mtu:'1500', 'rx-byte':'1000', 'tx-byte':'2000' },
        { '.id':'*2', name:'wlan1', type:'wlan', running:'false', disabled:'true' },
      ] : url.endsWith('/wireless') ? wirelessClients : url.includes('/traffic?') ? [{ 'rx-bits-per-second':'2400', 'tx-bits-per-second':'1200' }] : [{ identity:'Router' }];
      return { ok:true, json:async () => ({ measuredAt:sampleAt, rows, warning:'' }) };
    },
    window:{ addEventListener:(name, fn) => { events[name] = fn; }, HostTraffic:require('../../main/resources/static/host-traffic.js'), HostHistoryCharts:{ clear() { historyClearCount++; }, update(report) { elements['availability-chart'].report = report; } } },
  };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/availability-view.js', 'utf8'), context);
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/host.js', 'utf8'), context);
  await flush();
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
  assert.ok(ui.requests.some(r => r.url.endsWith('/mikrotik/wireless')));
  assert.equal(ui.panels[0].hidden, true);
  assert.equal(ui.panels[1].hidden, false);
  assert.equal(ui.timers.size, 1);
});
test('hiding the page stops all future live requests', async () => {
  const ui = await surface();
  ui.document.hidden = true; ui.events.visibilitychange();
  assert.equal(ui.timers.size, 0);
});

test('overview stays populated above the wireless tab', async () => {
  const ui = await surface();
  ui.tabs[1].onclick(); await flush();
  assert.ok(ui.requests.some(request => request.url.endsWith('/mikrotik/overview')));
  assert.ok(ui.elements['resource-details'].children.length);
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
  assert.match(ui.elements['history-range'].textContent, /1 minuto/);
  ui.elements['history-period'].value = '15';
  await ui.elements['history-period'].onchange();
  assert.ok(ui.requests.some(request => request.url.endsWith('/availability?minutes=15')));
});

test('wireless client opens grouped read-only details in a modal', async () => {
  const ui = await surface();
  ui.tabs[1].onclick(); await flush();
  ui.elements['wireless-clients'].children[0].children[0].children[0].children[0].onclick();
  assert.equal(ui.elements['wireless-modal'].open, true);
  assert.equal(ui.elements['wireless-modal-title'].textContent, 'Cliente A');
  const sections = ui.elements['wireless-details'].children[0].children;
  assert.deepEqual(sections.map(section => section.children[0].textContent), ['Identificação', 'Sinal e qualidade', 'Taxas e tráfego']);
  ui.elements['wireless-modal-close'].onclick();
  assert.equal(ui.elements['wireless-modal'].open, false);
});

test('wireless modal clears outdated details when the client disconnects', async () => {
  const clients = [{ 'mac-address':'AA:BB', interface:'wlan1' }];
  const ui = await surface({ wirelessClients:clients });
  ui.tabs[1].onclick(); await flush();
  ui.elements['wireless-clients'].children[0].children[0].children[0].children[0].onclick();
  clients.length = 0;
  await ui.timers.values().next().value.fn();
  assert.equal(ui.elements['wireless-details'].children.length, 0);
  assert.match(ui.elements['wireless-live-state'].textContent, /não consta mais/);
});

test('wireless table shows PPPoE login between MAC and interface and searches by login', async () => {
  const ui = await surface();
  ui.tabs[1].onclick(); await flush();
  const row = ui.elements['wireless-clients'].children[0].children[0];
  assert.equal(row.children[1].textContent, 'cliente.pppoe');
  assert.equal(row.children[2].textContent, 'wlan1');
  ui.elements['wireless-search'].value = 'cliente.pppoe';
  ui.elements['wireless-search'].oninput();
  assert.equal(ui.elements['wireless-clients'].children[0].children.length, 1);
  ui.elements['wireless-search'].value = 'outro nome';
  ui.elements['wireless-search'].oninput();
  assert.equal(ui.elements['wireless-clients'].children[0].children.length, 0);
});

test('wireless table shows numeric signal and CCQ percent without inventing absent readings', async () => {
  const ui = await surface({ wirelessClients:[
    { 'mac-address':'AA', 'avg-signal':'-71', 'avg-tx-ccq':'81' },
    { 'mac-address':'BB', 'avg-signal':'-63', 'avg-tx-ccq':'0' },
    { 'mac-address':'CC' },
  ] });
  ui.tabs[1].onclick(); await flush();
  const rows = ui.elements['wireless-clients'].children[0].children;
  assert.equal(rows[0].children[3].textContent, '-71 dBm');
  assert.equal(rows[0].children[5].textContent, '81%');
  assert.equal(rows[1].children[3].textContent, '-63 dBm');
  assert.equal(rows[1].children[5].textContent, '0%');
  assert.equal(rows[2].children[3].textContent, '—');
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
  assert.equal(rows()[0].children[7].textContent, '10.000');
  assert.equal(rows()[0].children[8].textContent, '12');
  assert.equal(rows()[2].children[7].textContent, '—');
  assert.equal(rows()[2].children[8].textContent, '—');
  for (const key of ['p-throughput', 'distance']) {
    ui.sortButtons.find(button => button.dataset.wirelessSort === key).onclick();
    assert.deepEqual(rows().map(row => row.children[0].children[0].textContent), ['BB','AA','CC']);
  }
});

test('wireless modal charts actual client bandwidth and preserves samples when sorting', async () => {
  const clients = [{ '.id':'*1', 'mac-address':'AA', interface:'wlan1', bytes:'1000,2000' }];
  const ui = await surface({ wirelessClients:clients });
  ui.tabs[1].onclick(); await flush();
  ui.elements['wireless-clients'].children[0].children[0].children[0].children[0].onclick();
  assert.equal(ui.elements['wireless-download'].textContent, 'Aguardando…');
  ui.advance(3000); clients[0].bytes = '751000,377000';
  await ui.timers.values().next().value.fn();
  assert.equal(ui.elements['wireless-download'].textContent, '2.000 kbps');
  assert.equal(ui.elements['wireless-upload'].textContent, '1.000 kbps');
  const plot = ui.elements['wireless-traffic-chart'].children[0];
  ui.sortButtons[0].onclick();
  assert.equal(ui.elements['wireless-traffic-chart'].children[0], plot);
  clients.length = 0; ui.advance(3000);
  await ui.timers.values().next().value.fn();
  assert.equal(ui.elements['wireless-download'].textContent, 'Indisponível');
  assert.equal(ui.elements['wireless-traffic-chart'].children.length, 0);
});

test('IXC is queried on demand, masks passwords and clears data when closing', async () => {
  const ui = await surface({ ixcLogins:[{ id:'7', ip:'192.0.2.5', pppoe:'cliente', pppoePassword:'secret', equipmentPassword:'router', connectionPort:'8080' }] });
  ui.tabs[1].onclick(); await flush();
  const row = ui.elements['wireless-clients'].children[0].children[0];
  row.children[0].children[0].onclick();
  assert.equal(ui.requests.filter(r => r.url.includes('/ixc/login?')).length, 0);
  await ui.elements['ixc-query'].onclick();
  assert.ok(ui.requests.at(-1).url.endsWith('/ixc/login?mac=AA%3ABB%3ACC%3ADD%3AEE%3AFF'));
  const section = ui.elements['ixc-details'].children[0];
  const password = section.children[1].children[2].children[1];
  assert.equal(password.children[0].type, 'password');
  assert.equal(password.children[0].value, 'secret');
  password.children[1].onclick();
  assert.equal(password.children[0].type, 'text');
  ui.elements['wireless-modal'].close();
  assert.equal(ui.elements['ixc-details'].children.length, 0);
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

test('wireless distinguishes rolling quality, instant alerts and disconnected last values with stable order', async () => {
  const clients = [
    { 'mac-address':'AA', interface:'wlan1', 'avg-signal':'-61', quality:'Normal', 'quality-severity':'0', 'quality-reason':'Sinal médio: -61 dBm — Normal', 'critical-alert':'SNR crítico agora: 10' },
    { 'mac-address':'BB', interface:'wlan1', quality:'Ruim', 'quality-severity':'2', 'connection-status':'disconnected' },
  ];
  const ui = await surface({ wirelessClients:clients }); ui.tabs[1].onclick(); await flush();
  const rows = () => ui.elements['wireless-clients'].children[0].children;
  assert.equal(rows()[0].dataset.clientMac, 'BB');
  assert.match(rows()[0].children[12].children[1].textContent, /Desconectado/);
  assert.equal(rows()[1].children[12].children[0].textContent, 'Normal');
  assert.match(rows()[1].children[12].children[1].textContent, /Crítico agora/);
  assert.match(rows()[1].children[12].children[0].title, /Sinal médio/);
  clients[0]['quality-severity'] = '3';
  await ui.timers.values().next().value.fn();
  assert.equal(rows()[0].dataset.clientMac, 'BB');
});
