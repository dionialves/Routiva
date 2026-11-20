const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const {webcrypto}=require('node:crypto');
global.crypto ||= webcrypto;
const MapSwitch=require('../../main/resources/static/map-switch.js');
const source=fs.readFileSync(require('node:path').join(__dirname,'../../main/resources/static/group-link-interfaces.js'),'utf8');
const context=vm.createContext({module:{exports:{}},MapSwitch});
vm.runInContext(source,context);
const registry=context.module.exports;
const hosts=[{id:'a',name:'A',groups:[{id:'g'}]},{id:'b',name:'B',groups:[{id:'g'}]},{id:'outside',groups:[{id:'other'}]}];
const entry=(hostId,name)=>({hostId,name,connector:'wireless',capacity:'unknown'});
test('a host may repeat with different interfaces but the same pair never repeats',()=>{
  assert.equal(registry.validate([entry('a','wifi0'),entry('a','wlan0'),entry('b','wifi0')],hosts,'g'),'');
  assert.match(registry.validate([entry('a','wifi0'),entry('a','wifi0')],hosts,'g'),/já estão habilitados/);
  assert.match(registry.validate([entry('outside','wifi0')],hosts,'g'),/não pertence/);
});
test('old group connections seed unique pairs without changing saved state',()=>{
  const node={id:'group'};
  const links=[{from:'group',to:'peer',connection:{hostA:'a',interfaceA:'wifi0'}},
    {from:'peer',to:'group',connection:{hostB:'a',interfaceB:'wifi0'}},
    {from:'group',to:'peer',connection:{hostA:'a',interfaceA:'eth0'}}];
  assert.deepEqual(Array.from(registry.seed(node,links),e=>[e.hostId,e.name]),[['a','wifi0'],['a','eth0']]);
  assert.equal(node.linkInterfaces,undefined);
  assert.equal(registry.seed({...node,linkInterfaces:[]},links).length,0);
});
test('group editor loads interfaces on demand and blocks duplicate additions',async()=>{
  class Element {
    constructor(tag){this.tag=tag;this.children=[];this.value='';this.hidden=false;this.disabled=false;this.classList={add(){},remove(){}};}
    get options(){return this.children;}
    append(...items){this.children.push(...items);}
    add(item){this.append(item);}
    replaceChildren(...items){this.children=[...items];}
    setAttribute(){} removeAttribute(){} focus(){} closest(){return {open:true};}
  }
  class Option extends Element {constructor(text,value){super('option');this.textContent=text;this.value=value;}}
  const elements=new Map();
  const document={getElementById(id){if(!elements.has(id))elements.set(id,new Element('div'));return elements.get(id);},createElement:tag=>new Element(tag)};
  const ctx=vm.createContext({module:{exports:{}},MapSwitch,document,Option,window:{setupGroupPicker:()=>({sync(){}})}});
  vm.runInContext(source,ctx);
  let calls=0;
  const editor=ctx.module.exports.create({hosts:()=>hosts,discover:async()=>{calls++;return [{name:'wifi0',source:'zabbix'},{name:'wlan0',source:'zabbix'}];}});
  editor.open({id:'group'},[]);editor.sync('g',true);
  const get=id=>document.getElementById('group-link-'+id);
  get('host').value='a';await get('host').onchange();
  assert.equal(calls,1);
  get('interface').value='wifi0';get('add').onclick();
  assert.equal(editor.value().length,1);
  assert.equal(get('editor').hidden,true);
  get('host').value='a'; await get('host').onchange();
  get('interface').value='wlan0';get('add').onclick();
  assert.equal(editor.value().length,2);
  assert.equal(editor.validate('g'),'');
  editor.open({id:'group',linkInterfaces:[entry('a','wifi0')]},[
    {id:'uplink',from:'group',to:'peer',connection:{hostA:'a',interfaceA:'wifi0'}}]);
  const remove=get('rows').children[0].children[4].children[0];
  remove.onclick();
  assert.equal(editor.value().length,1);
  assert.match(get('status').textContent,/uplink/);
  const pending=[];
  const racing=ctx.module.exports.create({hosts:()=>hosts,discover:id=>new Promise(resolve=>pending.push({id,resolve}))});
  racing.open({id:'group'},[]);racing.sync('g',true);
  get('host').value='a';const first=get('host').onchange();
  get('host').value='b';const second=get('host').onchange();
  pending[1].resolve([{name:'new-port',source:'zabbix'}]);await second;
  pending[0].resolve([{name:'stale-port',source:'zabbix'}]);await first;
  assert.equal(get('interface').options[1].value,'new-port');
  get('interface').value='new-port';get('add').onclick();
  assert.equal(racing.value()[0].hostId,'b');
  get('cancel').onclick();
  assert.equal(racing.value().length,1);
  assert.equal(get('editor').hidden,true);
  get('host').value='a';const cancelled=get('host').onchange();
  get('cancel').onclick();pending[2].resolve([{name:'cancelled-port',source:'zabbix'}]);await cancelled;
  assert.equal(get('interface').disabled,true);
  assert.equal(get('add').disabled,true);
});
