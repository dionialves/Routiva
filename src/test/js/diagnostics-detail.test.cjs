const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const flush = () => new Promise(resolve => setImmediate(resolve));
async function surface(report = null, { storage = new Map(), search = '', storageUnavailable = false } = {}) {
  class Element {
    constructor() { this.children=[]; this.listeners={}; this.dataset={}; this.style={}; this.value='1440'; this.checked=true; this.classList={toggle(){},remove(){}}; }
    append(...items) { this.children.push(...items); }
    replaceChildren(...items) { this.children=items; }
    setAttribute(key,value) { this[key]=value; }
    addEventListener(name,fn) { this.listeners[name]=fn; }
    emit(name, event = {}) { this.listeners[name]?.(event); }
    getBoundingClientRect() { return {width:600,left:0}; }
    closest() { return this; }
  }
  const elements = new Proxy({}, {get:(target,key)=>target[key] ||= new Element()});
  elements.period.options = ['60', '1440', '10080'].map(value => ({ value }));
  const timers = new Map(), requests=[]; let serial=0;
  const live = [{id:'h',ping:30,loss:2,state:'Respondendo',measuredAt:Date.now()}];
  const data = {
    '/api/diagnostics/groups': [{id:'g',name:'POP',hosts:1}],
    '/api/diagnostics/groups/g/details': {hosts:[{id:'h',name:'Router',ip:'10.0.0.1'}],equipment:{},incidents:{h:2},incidentSeverities:{h:{2:1,4:1}},warning:'',limits:{latency:20,lossNormal:.33,lossCritical:1}},
    '/api/diagnostics/groups/g/live':live,
  };
  let failLive=false, now=Date.now();
  class Clock extends Date { static now() { return now; } }
  const context={
    document:{hidden:false,body:new Element(),addEventListener(){},getElementById:id=>elements[id],createElement:()=>new Element(),createElementNS:()=>new Element(),createDocumentFragment:()=>new Element(),createTextNode:text=>text},
    location:{pathname:'/diagnostico/grupos/g',search},addEventListener(){},URLSearchParams,
    localStorage:{getItem:key=>{if(storageUnavailable)throw Error('unavailable');return storage.get(key);},setItem:(key,value)=>{if(storageUnavailable)throw Error('unavailable');storage.set(key,value);}},
    history:{replaceState(_state,_title,url){context.location.search=url.slice(url.indexOf('?'));}},
    AppIcon:()=>new Element(),AvailabilityView:{format:(v,u='%')=>v==null?'Sem dados':v+u,times(){}},
    AvailabilityChart:{paths:require('../../main/resources/static/availability-chart.js').paths,Chart:class { clear(){} update(){} draw(){} }},
    AbortSignal:{timeout(){}},Date:Clock,
    setTimeout(fn,ms) { timers.set(++serial,{fn,ms});return serial; },clearTimeout(id){timers.delete(id);},
    fetch:async url=>{requests.push(url);if(failLive&&url.includes('/live?')) throw Error('offline');return {ok:true,json:async()=>data[url.split('?')[0]]||{view:report ? {report} : null}};},
  };
  context.window=context;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/diagnostics.js'),'utf8'),context);
  await flush();
  return {elements,timers,requests,live,location:context.location,advance:ms=>{now+=ms;},setFailLive:()=>{failLive=true;},row:()=>elements['diagnostic-hosts'].children[0].children[0]};
}
test('period survives reload even when the original URL specified another period',async()=>{
  const storage=new Map();
  const ui=await surface(null,{storage,search:'?minutes=1440&other=kept'});
  ui.elements.period.value='60';ui.elements.period.emit('change');await flush();
  assert.equal(storage.get('routiva.diagnostics.period'),'60');
  assert.equal(new URLSearchParams(ui.location.search).get('other'),'kept');
  const reloaded=await surface(null,{storage,search:ui.location.search});
  assert.equal(reloaded.elements.period.value,'60');
  assert.ok(reloaded.requests.includes('/api/availability?minutes=60&groupId=g'));
  const reopened=await surface(null,{storage});
  assert.equal(reopened.elements.period.value,'60');
});
test('explicit valid period overrides storage and invalid preferences use the default',async()=>{
  const storage=new Map([['routiva.diagnostics.period','60']]);
  const explicit=await surface(null,{storage,search:'?minutes=10080'});
  assert.equal(explicit.elements.period.value,'10080');
  storage.set('routiva.diagnostics.period','invalid');
  const invalid=await surface(null,{storage,search:'?minutes=invalid'});
  assert.equal(invalid.elements.period.value,'1440');
  const unavailable=await surface(null,{storageUnavailable:true});
  assert.ok(unavailable.requests.includes('/api/availability?minutes=1440&groupId=g'));
});
test('live badges use configured thresholds and incidents do not imply connectivity',async()=>{
  const ui=await surface();
  ui.elements['diagnostic-ping'].emit('click'); await flush();
  const row=ui.row();
  assert.equal(row.children.length,7);
  assert.equal(row.children[0].children[0].dataset.state,'up');
  assert.equal(row.children[4].children[0].className,'diagnostic-incidents');
  assert.equal(row.children[4].children[0].children.length,2);
  assert.equal(row.children[4].children[0].children[0].className,'diagnostic-badge incident-severity-4');
  assert.equal(row.children[4].children[0].children[1].className,'diagnostic-badge incident-severity-2');
  assert.equal(row.children[4].children[0].children[0]['aria-label'],'Alta: 1 incidente');
  assert.equal(row.children[5].children[0].className,'diagnostic-badge warning');
  assert.equal(row.children[6].children[0].className,'diagnostic-badge critical');
  assert.match(ui.elements['incident-count'].textContent,/2 incidentes/);
  ui.live[0]={id:'h',ping:null,loss:null,state:'Coleta atrasada'};
  [...ui.timers.values()].find(t=>t.ms===2000).fn();await flush();
  assert.equal(row.children[0].children[0].dataset.state,'unknown');
  assert.equal(row.children[6].children[0].textContent,'Sem dados');
});
test('cards and charts refresh every ten seconds without a toggle',async()=>{
  const ui=await surface();
  assert.ok(![...ui.timers.values()].some(t=>t.ms===2000));
  const timer=[...ui.timers.values()].find(t=>t.ms===10000);
  assert.ok(timer);
  assert.equal(ui.elements['diagnostic-auto'].listeners.change,undefined);
  const row=ui.row();
  const before=ui.requests.length;
  await timer.fn(); await flush();
  assert.ok(ui.requests.length>before);
  assert.ok(ui.requests.slice(before).some(url=>url.includes('/availability')));
  assert.equal(ui.elements['diagnostic-reload'].listeners.click, undefined);
  assert.equal(ui.row(),row);
  assert.ok(!ui.requests.slice(before).some(url=>url.includes('/details')||url.includes('/live?')));
});
test('live fetch failure clears previous connectivity and numeric badges',async()=>{
  const ui=await surface();
  ui.elements['diagnostic-ping'].emit('click'); await flush();
  ui.setFailLive();
  [...ui.timers.values()].find(t=>t.ms===2000).fn();await flush();
  const row=ui.row();
  assert.equal(row.children[0].children[0].dataset.state,'unknown');
  assert.equal(row.children[5].children[0].textContent,'Indisponível');
  assert.equal(row.children[6].children[0].className,'diagnostic-badge neutral');
});

test('secondary charts expose real interval values and missing data through keyboard tooltips',async()=>{
  const report={from:0,to:180000,evaluatedAt:180000,version:'test',total:{},series:[
    {from:0,to:60000,metrics:{latency:5,loss:0}},
    {from:60000,to:120000,metrics:{latency:null,loss:null}},
    {from:120000,to:180000,metrics:{latency:30,loss:100}},
  ]};
  const ui=await surface(report);
  const [svg,tooltip]=ui.elements['latency-chart'].children;
  svg.emit('focus'); assert.match(tooltip.textContent,/5 ms/);
  svg.emit('keydown',{key:'ArrowRight',preventDefault(){}});
  assert.match(tooltip.textContent,/Sem dados/);
  svg.emit('keydown',{key:'End',preventDefault(){}});
  assert.match(tooltip.textContent,/30 ms/);
  svg.emit('keydown',{key:'Escape'});assert.equal(tooltip.hidden,true);
});

test('live averaging starts on play and resets only when ping is restarted', async () => {
  const ui = await surface();
  ui.elements['diagnostic-ping'].emit('click'); await flush();
  const since = () => new URL(ui.requests.filter(url => url.includes('/live?')).at(-1), 'http://localhost').searchParams.get('since');
  const opened = since();
  assert.ok(Number(opened) > 0);
  ui.advance(60000);
  await [...ui.timers.values()].find(timer => timer.ms === 10000).fn();
  await flush();
  assert.equal(since(), opened);
  ui.elements['diagnostic-ping'].emit('click');
  ui.elements['diagnostic-ping'].emit('click');
  await flush();
  assert.equal(Number(since()), Number(opened) + 60000);
  assert.equal(ui.requests.filter(url => url.endsWith('/details')).length, 1);
});


test('ping starts paused, toggles stop and play, and ignores a pending response after stop', async () => {
  const ui = await surface();
  const button = ui.elements['diagnostic-ping'];
  assert.equal(ui.requests.some(url => url.includes('/live?')), false);
  button.emit('click');
  assert.equal(button['aria-label'], 'Parar ping');
  button.emit('click');
  await flush();
  assert.equal(button['aria-label'], 'Iniciar ping');
  assert.equal(ui.row().children[5].children[0].textContent, 'Aguardando');
  assert.equal([...ui.timers.values()].some(t => t.ms === 2000), false);
  ui.advance(1000);
  button.emit('click'); await flush();
  assert.equal(ui.row().children[0].children[0].dataset.state, 'up');
  button.emit('click');
  assert.equal([...ui.timers.values()].some(t => t.ms === 2000), false);
  assert.equal(ui.row().children[0].children[0].dataset.state, 'up');
});
