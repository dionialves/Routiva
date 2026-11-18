const {test} = require('node:test');
const assert = require('node:assert/strict');
const {webcrypto} = require('node:crypto');
global.crypto ||= webcrypto;
const switchMap = require('../../main/resources/static/map-switch.js');

test('mixed ports preserve identities when quantities grow or shrink', () => {
  const ports = switchMap.portsFor([], {rj45: 24, sfp: 4, qsfp: 2});
  assert.equal(ports.length, 30);
  const changed = switchMap.portsFor(ports, {rj45: 12, sfp: 6, qsfp: 2});
  assert.deepEqual(changed.filter(p => p.type === 'rj45'), ports.slice(0, 12));
  assert.deepEqual(changed.filter(p => p.type === 'qsfp'), ports.slice(-2));
  assert.equal(new Set(changed.map(p => p.id)).size, 20);
});
test('ports stay inside chassis with distinct positions up to 96 ports', () => {
  for (const count of [0, 1, 2, 9, 10, 11, 30, 48, 96]) {
    const node = {ports: switchMap.portsFor([], {rj45: count, sfp: 0, qsfp: 0})};
    const geometry = switchMap.geometry(node);
    assert.equal(new Set(geometry.ports.map(p => `${p.x}:${p.y}`)).size, count);
    for (const port of geometry.ports) {
      assert.ok(Math.abs(port.x) + 12 < geometry.width / 2);
      assert.ok(Math.abs(port.y) + 11 < geometry.height / 2);
    }
  }
});
test('connection endpoints follow equipment movement and preserve legacy centers', () => {
  const node = {x: 10, y: 20, ports: [{id: 'uplink', type: 'qsfp'}]};
  const start = switchMap.endpoint(node, 'uplink');
  node.x += 200; node.y -= 40;
  assert.deepEqual(switchMap.endpoint(node, 'uplink'), {x: start.x + 200, y: start.y - 40});
  assert.deepEqual(switchMap.endpoint(node, null), {x: 210, y: -20});
});

test('all ports are compact and two optical ports remain side by side in a separate group', () => {
  const shape = switchMap.geometry({ports: switchMap.portsFor([], {rj45:24,sfp:2,qsfp:0})});
  const copper = shape.ports.filter(p => p.type === 'rj45');
  const fiber = shape.ports.filter(p => p.type === 'sfp');
  assert.equal(copper[2].x - copper[0].x,23);
  assert.equal(copper[1].y - copper[0].y,-25);
  assert.equal(fiber[0].y,fiber[1].y);
  assert.equal(fiber[1].x - fiber[0].x,23);
  assert.ok(fiber[0].x - Math.max(...copper.map(p => p.x)) >= 46);
});
test('different port types share the same compact rows regardless of quantity', () => {
  const shape = switchMap.geometry({ports: switchMap.portsFor([], {rj45:4,sfp:24,qsfp:4})});
  const copper = shape.ports.filter(p => p.type === 'rj45');
  const fiber = shape.ports.filter(p => p.type === 'sfp');
  assert.equal(new Set(copper.map(p => p.x)).size,2);
  assert.equal(new Set(copper.map(p => p.y)).size,2);
  assert.equal(copper[2].x - copper[0].x,23);
  assert.equal(copper[1].y - copper[0].y,-25);
  assert.equal(fiber[2].x - fiber[0].x,23);
  assert.equal(fiber[1].y - fiber[0].y,-25);
});

test('similar quantities use compact spacing regardless of port type', () => {
  const shape = switchMap.geometry({ports: switchMap.portsFor([], {rj45:0,sfp:24,qsfp:20})});
  for (const type of ['sfp','qsfp']) {
    const ports = shape.ports.filter(port => port.type === type);
    assert.equal(ports[2].x - ports[0].x,23);
    assert.equal(ports[1].y - ports[0].y,-25);
  }
});

test('up to ten ports use one row and larger switches retain two rows', () => {
  for (const type of switchMap.types) {
    for (const count of [1,2,3,4,5,9,10,11,12]) {
      const counts = {rj45:0,sfp:0,qsfp:0,[type]:count};
      const shape = switchMap.geometry({ports:switchMap.portsFor([],counts)});
      const ports = shape.ports;
      assert.equal(ports[0].y,29.5);
      if (count <= 10) {
        assert.equal(new Set(ports.map(port => port.y)).size,1);
        assert.equal(new Set(ports.map(port => port.x)).size,count);
        if (count > 1) assert.equal(ports[1].x - ports[0].x,23);
      }
      if (count > 10) {
        for (let i=0; i<count; i++) assert.equal(ports[i].y,i%2 ? 4.5 : 29.5);
        assert.equal(ports[0].x,ports[1].x);
        assert.ok(ports[2].x > ports[0].x);
      }
    }
  }
});

test('slots generate named interfaces for every capacity and retain IDs on rename or capacity change', () => {
  const slots = Object.keys(switchMap.capacities).map((capacity,i) => ({id:'slot'+i,name:i===0?'1/1/':'sfp'+i+'/',capacity,count:3}));
  const ports = switchMap.portsForSlots([],slots);
  assert.equal(ports.length,15);
  assert.equal(ports[0].name,'1/1/1');
  slots[0].name='ether'; slots[0].capacity='sfp100g'; slots[0].count=4;
  const changed=switchMap.portsForSlots(ports,slots);
  assert.equal(changed[0].id,ports[0].id);
  assert.equal(changed[0].name,'ether1');
  assert.equal(changed[0].type,'qsfp');
  assert.equal(changed[0].capacity,'sfp100g');
  assert.equal(changed.length,16);
});
test('legacy interface migration preserves connection identities and separates slots of the same capacity', () => {
  const node={ports:[{id:'old1',type:'rj45'},{id:'old2',type:'sfp'}]};
  const slots=switchMap.slotsFor(node);
  const ports=switchMap.portsForSlots(node.ports,slots);
  assert.deepEqual(ports.map(p=>p.id),['old1','old2']);
  slots.push({id:'extra',name:'sfp2/',capacity:'sfp10g',count:2});
  const changed=switchMap.portsForSlots(ports,slots);
  const shape=switchMap.geometry({ports:changed});
  assert.ok(shape.ports[2].x-shape.ports[1].x>=46);
  assert.match(shape.ports[2].label,/sfp2\/1.*SFP10G/);
});
test('slot validation accepts many slots while rejecting blank names and invalid counts', () => {
  const slots=Array.from({length:10},(_,i)=>({id:'slot'+i,name:'ether'+i+'/',capacity:'electrical',count:24}));
  assert.equal(switchMap.portsForSlots([],slots).length,240);
  assert.equal(switchMap.portsForSlots([],[{...slots[0],name:''}]),null);
  assert.equal(switchMap.portsForSlots([],[{...slots[0],count:0}]),null);
  assert.equal(switchMap.portsForSlots([],[{...slots[0],capacity:'unknown'}]),null);
});

test('disabled interfaces hide ports without deleting them and use equipment centers for connections', () => {
  const node = {icon:'switch',interfacesEnabled:false,x:10,y:20,ports:[{id:'p',type:'sfp'}]};
  const make = (tag, attributes) => ({tag,attributes,children:[],append(...children){this.children.push(...children);}});
  const group = make('g',{});
  switchMap.draw(make,group,node,'green',false);
  assert.equal(group.children.filter(child=>child.attributes.class==='switch-port').length,0);
  assert.equal(node.ports.length,1);
  assert.deepEqual(switchMap.endpoint(node,'p'),{x:10,y:20});
  node.interfacesEnabled = true;
  assert.notDeepEqual(switchMap.endpoint(node,'p'),{x:10,y:20});
  node.icon = 'circle';
  assert.deepEqual(switchMap.endpoint(node,'p'),{x:10,y:20});
});

test('switch has a single live indicator with ping colors independent from incident color', () => {
  const make=(tag,attributes)=>({tag,attributes,children:[],append(...children){this.children.push(...children);}});
  for (const [state,color] of [['online','#58bd83'],['offline','#ff0000'],['unknown','#687486']]) {
    const group=make('g',{});
    switchMap.draw(make,group,{hostId:'h',ports:[]},'#fced4e',false,{pingState:state});
    const indicators=group.children.filter(child=>child.attributes.class==='switch-status-led');
    assert.equal(indicators.length,1);
    assert.equal(indicators[0].attributes.fill,color);
  }
});

test('custom switch tint applies only to its cabinet and never to ports or the ping LED',()=>{
  const make=(tag,attributes)=>({tag,attributes,children:[],append(...children){this.children.push(...children);}});
  const group=make('g',{});
  switchMap.draw(make,group,{switchColor:'#2288cc',ports:[{id:'p',type:'sfp'}]},'gray',false,{pingState:'online'});
  const cabinet=group.children.find(child=>child.tag==='image');
  assert.match(cabinet.attributes.filter,/^url\(#switch-tint-/);
  const port=group.children.find(child=>child.attributes.class==='switch-port');
  assert.equal(port.children.find(child=>child.tag==='image').attributes.filter,undefined);
  assert.equal(group.children.find(child=>child.attributes.class==='switch-status-led').attributes.fill,'#58bd83');
  const original=make('g',{});
  switchMap.draw(make,original,{ports:[]},'gray',false);
  assert.equal(original.children.find(child=>child.tag==='image').attributes.filter,undefined);
});

test('connected interfaces show their plugged images and deleting the link restores empty ports',()=>{
  const ports=[{id:'40',type:'qsfp',capacity:'sfp40g'},{id:'100',type:'qsfp',capacity:'sfp100g'},
    {id:'10',type:'sfp',capacity:'sfp10g'}];
  const node={id:'sw',icon:'switch',ports};
  const make=(tag,attributes)=>({tag,attributes,children:[],append(...children){this.children.push(...children);}});
  const render=links=>{
    const root=make('g',{});
    switchMap.draw(make,root,node,'gray',false,{connectedPorts:switchMap.connectedPorts(node,links)});
    return root.children.filter(child=>child.attributes.class==='switch-port');
  };
  const links=ports.map(port=>({from:'sw',fromPort:port.id,to:'other',toPort:'other-port'}));
  const occupied=render(links);
  assert.equal(occupied[0].children.filter(child=>child.attributes.class==='switch-port-plug').length,1);
  assert.match(occupied[0].children[0].textContent,/Em uso/);
  assert.ok(occupied.every(port=>port.children.some(child=>child.attributes.class==='switch-port-plug')));
  assert.ok(render([]).every(port=>!port.children.some(child=>child.attributes.class==='switch-port-plug')));
  assert.equal(switchMap.connectedPorts({id:'other'},links).has('other-port'),true);
});
test('wire anchors start inside each module front and follow the port row',()=>{
  const node={id:'sw',icon:'switch',x:100,y:200,ports:[
    {id:'lower',type:'qsfp',capacity:'sfp40g'},
    {id:'upper',type:'qsfp',capacity:'sfp40g'},
    {id:'third',type:'qsfp',capacity:'sfp40g'},
    {id:'100',type:'qsfp',capacity:'sfp100g'},
    ...Array.from({length:7},(_,i)=>({id:'extra'+i,type:'qsfp',capacity:'sfp40g'}))]};
  const geometry=switchMap.geometry(node);
  for(const [id,direction] of [['lower',1],['upper',-1]]) {
    const port=geometry.ports.find(p=>p.id===id);
    assert.equal(switchMap.cableDirection(node,id),direction);
    assert.deepEqual(switchMap.endpoint(node,id),{x:100+port.x,y:200+port.y+direction*4.2});
  }
  const port100=geometry.ports.find(p=>p.id==='100');
  assert.equal(switchMap.cableDirection(node,'100'),-1);
  assert.deepEqual(switchMap.endpoint(node,'100'),{x:100+port100.x,y:200+port100.y-2.6});
  node.interfacesEnabled=false;
  assert.equal(switchMap.cableDirection(node,'lower'),0);
  assert.deepEqual(switchMap.endpoint(node,'lower'),{x:100,y:200});
});

test('every capacity uses the supplied connected art and its own socket, with neutral empty 40G',()=>{
  const specs=[['electrical','rj45','rj45-plugged',3.6],['sfp1g','sfp','sfp-plugged',4.2],
    ['sfp10g','sfp','sfp-plugged',4.2],['sfp40g','qsfp','qsfp40-plugged',4.2],['sfp100g','qsfp','qsfp100-plugged',2.6]];
  const make=(tag,attributes)=>({tag,attributes,children:[],append(...children){this.children.push(...children);}});
  for(const [capacity,type,asset,socketY] of specs) {
    const node={id:'sw',icon:'switch',x:10,y:20,ports:[{id:'p',type,capacity}]};
    const root=make('g',{});
    switchMap.draw(make,root,node,'gray',false,{connectedPorts:new Set(['p'])});
    const port=root.children.find(child=>child.attributes.class==='switch-port');
    const plug=port.children.find(child=>child.attributes.class==='switch-port-plug');
    assert.match(plug.children[0].attributes.href,new RegExp('/'+asset+'\\.svg'));
    const position=switchMap.geometry(node).ports[0];
    assert.deepEqual(switchMap.endpoint(node,'p'),{x:10+position.x,y:20+position.y+socketY});
    if(capacity==='sfp40g') {
      const empty=make('g',{});switchMap.draw(make,empty,node,'gray',false);
      const emptyPort=empty.children.find(child=>child.attributes.class==='switch-port');
      assert.match(emptyPort.children.find(child=>child.tag==='image').attributes.href,/\/qsfp40\.svg/);
    }
  }
});

test('electrical and SFP 1G/10G plugs stay upright in both rows and their cables follow the socket',()=>{
  const make=(tag,attributes)=>({tag,attributes,children:[],append(...children){this.children.push(...children);}});
  for(const [capacity,type,socketY] of [['electrical','rj45',3.6],['sfp1g','sfp',4.2],['sfp10g','sfp',4.2]]) {
    const node={id:'sw',icon:'switch',x:100,y:200,ports:Array.from({length:11},(_,i)=>({id:'p'+i,type,capacity}))};
    const root=make('g',{});
    switchMap.draw(make,root,node,'gray',false,{connectedPorts:new Set(node.ports.map(port=>port.id))});
    const ports=root.children.filter(child=>child.attributes.class==='switch-port');
    for(const port of ports) assert.equal(port.children.find(child=>child.attributes.class==='switch-port-plug').attributes.transform,'scale(1 1)');
    const upper=switchMap.geometry(node).ports[1];
    assert.deepEqual(switchMap.endpoint(node,'p1'),{x:100+upper.x,y:200+upper.y+socketY});
  }
});
