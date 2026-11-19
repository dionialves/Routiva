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
test('small slots stay in one row beside larger slots', () => {
  const shape = switchMap.geometry({ports: switchMap.portsFor([], {rj45:4,sfp:24,qsfp:4})});
  const copper = shape.ports.filter(p => p.type === 'rj45');
  const fiber = shape.ports.filter(p => p.type === 'sfp');
  assert.equal(new Set(copper.map(p => p.x)).size,4);
  assert.equal(new Set(copper.map(p => p.y)).size,1);
  assert.equal(copper[1].x - copper[0].x,23);
  assert.equal(copper[1].y,copper[0].y);
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

test('slots with up to twelve ports use one row and larger slots retain two rows', () => {
  for (const type of switchMap.types) {
    for (const count of [1,2,3,4,5,9,10,11,12,13,24]) {
      const counts = {rj45:0,sfp:0,qsfp:0,[type]:count};
      const shape = switchMap.geometry({ports:switchMap.portsFor([],counts)});
      const ports = shape.ports;
      assert.equal(ports[0].y,29.5);
      if (count <= 12) {
        assert.equal(new Set(ports.map(port => port.y)).size,1);
        assert.equal(new Set(ports.map(port => port.x)).size,count);
        if (count > 1) assert.equal(ports[1].x - ports[0].x,23);
      }
      if (count > 12) {
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
    ...Array.from({length:9},(_,i)=>({id:'extra'+i,type:'qsfp',capacity:'sfp40g'}))]};
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
    const node={id:'sw',icon:'switch',x:100,y:200,ports:Array.from({length:13},(_,i)=>({id:'p'+i,type,capacity}))};
    const root=make('g',{});
    switchMap.draw(make,root,node,'gray',false,{connectedPorts:new Set(node.ports.map(port=>port.id))});
    const ports=root.children.filter(child=>child.attributes.class==='switch-port');
    for(const port of ports) assert.equal(port.children.find(child=>child.attributes.class==='switch-port-plug').attributes.transform,'scale(1 1)');
    const upper=switchMap.geometry(node).ports[1];
    assert.deepEqual(switchMap.endpoint(node,'p1'),{x:100+upper.x,y:200+upper.y+socketY});
  }
});


test('discovery preserves exact names, metadata and existing connection identities', () => {
  const result = switchMap.importInterfaces([
    {name:'uplink-core', 'default-name':'sfp-sfpplus1', comment:'Core', supported:'1G-full,10G-full', rate:'10Gbps', running:'true'},
    {name:'ether7', 'default-name':'ether7', supported:'10M-half,100M-full,1G-full', speed:'1Gbps', running:'false'},
    {name:'ether9', 'default-name':'ether9', supported:'10M-half,100M-full,1G-full', speed:'1Gbps', running:'true', disabled:'true'},
    {name:'uplink-core'}
  ], [{id:'connected-port',name:'uplink-core'}]);
  const ports = switchMap.portsForSlots(result.ports, result.slots);
  assert.deepEqual(result.slots.map(slot => [slot.name, slot.count]), [['sfp-sfpplus', 1], ['ether', 2]]);
  assert.equal(ports.length, 3);
  assert.equal(ports[0].id, 'connected-port');
  assert.equal(ports[0].name, 'uplink-core');
  assert.equal(ports[0].comment, 'Core');
  assert.equal(ports[0].capacity, '10g');
  assert.equal(ports[0].connector, 'sfpplus');
  assert.equal(ports[0].speed, '10Gbps');
  assert.equal(ports[0].running, true);
  assert.deepEqual(ports.slice(1).map(p => [p.name,p.running]), [['ether7',false],['ether9',false]]);
  const again = switchMap.importInterfaces([{name:'ether7','default-name':'ether7'},
    {name:'uplink-core','default-name':'sfp-sfpplus1'}], ports);
  assert.equal(again.ports[1].id, 'connected-port');
  assert.equal(again.ports[0].running, null);
});


test('slot prefixes come from equipment interface names and separate families of equal capacity', () => {
  const result = switchMap.importInterfaces([
    {name:'LAN1', 'default-name':'ether1'},
    {name:'LAN2', 'default-name':'ether2'},
    {name:'ge-0/0/7'},
    {name:'ge-0/0/9'},
    {name:'management'}
  ], []);
  assert.deepEqual(result.slots.map(slot => [slot.name, slot.count]),
    [['ether',2], ['ge-0/0/',2], ['management',1]]);
  assert.deepEqual(switchMap.portsForSlots(result.ports, result.slots).map(port => port.name),
    ['LAN1','LAN2','ge-0/0/7','ge-0/0/9','management']);
});


test('connectors and capacities are independent and legacy migration keeps linked port identities', () => {
  const legacy = {id:'slot',name:'ether',capacity:'electrical',count:1};
  const previous = switchMap.portsForSlots([], [legacy]);
  const migrated = switchMap.normalizeSlot(legacy);
  assert.equal(migrated.connector,'electrical');
  assert.equal(migrated.capacity,'unknown');
  for (const [connector,info] of Object.entries(switchMap.connectors)) {
    for (const capacity of Object.keys(switchMap.portCapacities)) {
      const ports = switchMap.portsForSlots(previous,[{...migrated,connector,capacity}]);
      assert.equal(ports[0].id,previous[0].id);
      assert.equal(ports[0].connector,connector);
      assert.equal(ports[0].capacity,capacity);
      assert.equal(ports[0].type,info.type);
      assert.ok(switchMap.portVisual(ports[0]));
    }
  }
  assert.equal(switchMap.portsForSlots([], [{...migrated,connector:'invalid'}]),null);
  assert.equal(switchMap.portsForSlots([], [{...migrated,capacity:'invalid'}]),null);
});

test('discovery uses supported port speed independently from negotiated link speed', () => {
  const result=switchMap.importInterfaces([
    {name:'ether1',supported:'10M-baseT-half,100M-baseT-full,1G-baseT-full',rate:'100Mbps'},
    {name:'sfp28-1',supported:'1G-baseX,10G-baseSR-LR,25G-baseSR-LR',rate:'10Gbps'},
    {name:'qsfp-dd800-1',rate:'800Gbps'},
    {name:'ether2'}
  ],[]);
  assert.deepEqual(result.ports.map(p=>[p.connector,p.capacity]),
    [['electrical','1g'],['sfp28','25g'],['qsfpdd800','unknown'],['electrical','unknown']]);
  assert.equal(result.ports[0].speed,'100Mbps');
});


test('physical maximum never falls back to negotiated rate or configured speed', () => {
  const result=switchMap.importInterfaces([
    {name:'ether1',supported:'10M-half,100M-full,1G-full',rate:'10Mbps',speed:'100Mbps'},
    {name:'ether2',supported:'10M-half,100M-full,1G-full',status:'no-link'},
    {name:'sfp-sfpplus1',supported:'1G-baseX',rate:'1Gbps'},
    {name:'ether3',rate:'1Gbps',speed:'1Gbps'},
    {name:'sfp28-1','supported-rates':'1G-full,10G-full,25G-full',rate:'1Gbps'},
    {name:'ether4',supported:'unavailable',rate:'100Mbps'}
  ],[]);
  assert.deepEqual(result.ports.map(port=>port.capacity), ['1g','1g','10g','unknown','25g','unknown']);
});


test('native optical interface names identify physical capacity without a module', () => {
  const result=switchMap.importInterfaces([
    {name:'sfp1'}, {name:'sfp-sfpplus2'}, {name:'sfp28-1'}, {name:'sfp56-1'},
    {name:'qsfpplus1-1'}, {name:'qsfp28-1-1'}, {name:'qsfp56-1-1'}, {name:'qsfp56-dd-1-1'},
    {name:'Core', 'default-name':'sfp-sfpplus1', supported:'1G-baseX', rate:'1Gbps'},
    {name:'uplink-sfp28-1'}, {name:'sfp999-custom'}, {name:'qsfp-dd800-1'}, {name:'ether1',rate:'1Gbps'}
  ],[]);
  assert.deepEqual(result.ports.map(p=>p.capacity),
    ['1g','10g','25g','50g','40g','100g','200g','400g','10g','unknown','unknown','unknown','unknown']);
  assert.equal(result.ports[7].connector,'qsfpdd');
  assert.equal(result.ports[8].name,'Core');
});


test('electrical ports use their own advertised maximum when physical supported modes are absent', () => {
  const result=switchMap.importInterfaces([
    {name:'ether1',rate:'100Mbps',advertising:'10M-half,10M-full,100M-half,100M-full,1000M-full',
      'link-partner-advertising':'10M-full,100M-full'},
    {name:'ether2',status:'no-link',advertising:'10M-half,100M-full,1000M-full'},
    {name:'ether3',supported:'10M-half,100M-full,1000M-full',advertising:'100M-full'},
    {name:'ether4',rate:'1Gbps','link-partner-advertising':'1000M-full'},
    {name:'ether5',advertising:'100M-full,10000M-full'}
  ],[]);
  assert.deepEqual(result.ports.map(port=>port.capacity), ['1g','1g','1g','unknown','10g']);
  assert.equal(result.ports[0].speed,'100Mbps');
});

test('Datacom imports explicit connector and supported capacity without using negotiated speed', () => {
  const result = switchMap.importInterfaces([{
    name: 'ten-gigabit-ethernet-1/1/1', type: 'ether', connector: 'sfpplus',
    supported: '1 Gbps, 10 Gbps', rate: '1Gbps', running: 'true',
  }], []);
  assert.equal(result.ports[0].connector, 'sfpplus');
  assert.equal(result.ports[0].capacity, '10g');
  assert.equal(result.ports[0].speed, '1Gbps');
  const unknown = switchMap.importInterfaces([{
    name: 'ten-gigabit-ethernet-1/1/2', connector: 'sfpplus', rate: '10Gbps',
  }], []);
  assert.equal(unknown.ports[0].capacity, 'unknown');
});

test('grouped Datacom families keep their ports separate even with the same chassis slot and port', () => {
  const rows = [
    ...Array.from({ length: 24 }, (_, i) => ({ name: `ten-gigabit-ethernet-1/1/${i + 1}`, type: 'ether', running: 'true', disabled: 'false', rate: '10Gbps' })),
    ...Array.from({ length: 2 }, (_, i) => ({ name: `forty-gigabit-ethernet-1/1/${i + 1}`, type: 'ether', running: 'true', disabled: 'false', rate: '40Gbps' })),
  ];
  const imported = switchMap.importInterfaces(rows, []);
  assert.equal(imported.ports.length, 26);
  assert.deepEqual(imported.slots.map(slot => slot.count), [24, 2]);
  assert.equal(new Set(imported.ports.map(port => port.name)).size, 26);
  assert.equal(new Set(imported.ports.map(port => port.id)).size, 26);
  assert.equal(imported.ports[24].speed, '40Gbps');
  assert.equal(imported.ports[24].capacity, 'unknown');
});

test('DM4250 hardware metadata imports SFP+ 10G and QSFP+ 40G for down ports and preserves cable identities', () => {
  const rows = [
    ...Array.from({ length: 24 }, (_, i) => ({ name: `ten-gigabit-ethernet-1/1/${i + 1}`, type: 'ether', connector: 'sfpplus', supported: '10 Gbps', running: 'false', disabled: 'true' })),
    ...Array.from({ length: 2 }, (_, i) => ({ name: `forty-gigabit-ethernet-1/1/${i + 1}`, type: 'ether', connector: 'qsfpplus', supported: '40 Gbps', running: 'false', disabled: 'false' })),
  ];
  const previous = switchMap.importInterfaces(rows.map(({ connector, supported, ...row }) => row), []);
  const imported = switchMap.importInterfaces(rows, previous.ports);
  assert.deepEqual(imported.slots.map(slot => [slot.connector, slot.capacity, slot.count]), [
    ['sfpplus', '10g', 24], ['qsfpplus', '40g', 2],
  ]);
  assert.deepEqual(imported.ports.map(port => port.id), previous.ports.map(port => port.id));
  assert.ok(imported.ports.every(port => port.running === false));
});

test('HTTP deployments without randomUUID can import ports, add slots and preserve existing ids', () => {
  const vm = require('node:vm');
  const fs = require('node:fs');
  const context = vm.createContext({ module: { exports: {} }, crypto: { getRandomValues: webcrypto.getRandomValues.bind(webcrypto) } });
  vm.runInContext(fs.readFileSync('src/main/resources/static/map-switch.js', 'utf8'), context);
  const api = context.module.exports;
  const rows = Array.from({ length: 26 }, (_, i) => ({ name: `ten-gigabit-ethernet-1/1/${i + 1}`, connector: 'sfpplus', supported: '10 Gbps' }));
  const imported = api.importInterfaces(rows, []);
  assert.equal(imported.ports.length, 26);
  const ids = [...imported.slots, ...imported.ports].map(item => item.id);
  assert.equal(new Set(ids).size, ids.length);
  assert.ok(ids.every(id => /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id)));
  const slots = [...imported.slots, { id: api.newId(), name: 'extra', connector: 'sfpplus', capacity: '10g', count: 1 }];
  const ports = api.portsForSlots(imported.ports, slots);
  assert.equal(ports.length, 27);
  assert.deepEqual(Array.from(ports.slice(0, 26), port => port.id), Array.from(imported.ports, port => port.id));
});

test('radio places at most four interfaces below the cabinet with shared cable anchors', () => {
  const node = {icon: 'radio', x: 100, y: 200, ports: Array.from({length: 4}, (_, i) =>
    ({id: 'radio-' + i, type: 'rj45', connector: 'electrical', name: 'ether' + (i + 1)}))};
  const shape = switchMap.geometry(node);
  assert.equal(shape.ports.length, 4);
  assert.equal(new Set(shape.ports.map(port => port.x)).size, 4);
  for (const port of shape.ports) {
    assert.equal(port.y, 65);
    assert.equal(switchMap.cableDirection(node, port.id), 1);
    assert.deepEqual(switchMap.endpoint(node, port.id), {x: node.x + port.x, y: 268.6});
  }
  assert.equal(switchMap.isEquipment(node), true);
});
