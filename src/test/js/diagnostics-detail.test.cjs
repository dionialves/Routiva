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
    AbortSignal:{timeout(){}},Date:Clock,
    setTimeout(fn,ms) { timers.set(++serial,{fn,ms});return serial; },clearTimeout(id){timers.delete(id);},
    fetch:async url=>{requests.push(url);if(failLive&&url.includes('/live?')) throw Error('offline');return {ok:true,json:async()=>data[url.split('?')[0]]||{view:report ? {report} : null}};},
  };
  context.window=context;
  vm.runInNewContext(require('node:fs').readFileSync('src/main/resources/static/host-menu.js', 'utf8'), context);
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/diagnostics.js'),'utf8'),context);
  await flush();
  return {elements,timers,requests,live,location:context.location,advance:ms=>{now+=ms;},setFailLive:()=>{failLive=true;},row:()=>elements['diagnostic-hosts'].children[0].children[0]};
}
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
test('group opens only the host table without history requests or background refresh', async () => {
  const ui = await surface();
  assert.equal(ui.row().children.length, 7);
  assert.equal(ui.requests.some(url => url.includes('/availability')), false);
  assert.equal(ui.timers.size, 0);
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

test('live averaging starts on play and resets only when ping is restarted', async () => {
  const ui = await surface();
  ui.elements['diagnostic-ping'].emit('click'); await flush();
  const since = () => new URL(ui.requests.filter(url => url.includes('/live?')).at(-1), 'http://localhost').searchParams.get('since');
  const opened = since();
  assert.ok(Number(opened) > 0);
  ui.advance(60000);
  await [...ui.timers.values()].find(timer => timer.ms === 2000).fn();
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
