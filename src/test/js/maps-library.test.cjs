const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../../main/resources/static/maps.js'), 'utf8');
test('library derives map type from all group bindings, including maps outside the search', () => {
  const rows = [];
  const fields = new Map();
  const $ = id => {
    if (!fields.has(id)) fields.set(id, {value:'', replaceChildren(){rows.length=0;},
      insertRow(){const cells=[]; rows.push(cells); return {insertCell(){const cell={append(node){this.child=node;}};cells.push(cell);return cell;}};}});
    return fields.get(id);
  };
  const maps = [
    {id:'parent',layout:{name:'Principal',nodes:[{groupId:'g',subMapId:'child'}],links:[]}},
    {id:'child',layout:{name:'Filial',nodes:[],links:[]}},
    {id:'free',layout:{name:'Livre',nodes:[{subMapId:'free'}],links:[]}}
  ];
  const makeElement = () => ({setAttribute(){},addEventListener(){},append(){}});
  const context = vm.createContext({$,maps,page:0,pageSize:10,favoriteMaps:[],favoriteSaving:false,svg:makeElement,document:{createElement:makeElement}});
  vm.runInContext(source.slice(source.indexOf('function renderList('),source.indexOf("$('map-search').oninput")),context);
  vm.runInContext('renderList()',context);
  assert.equal(rows.length,3);
  assert.ok(rows.every(row=>row.length===6));
  assert.deepEqual(rows.map(row=>row[5].textContent),['Principal','Sub-mapa','Principal']);
  $('map-search').value='Filial';
  vm.runInContext('renderList()',context);
  assert.equal(rows.length,1);
  assert.equal(rows[0][5].textContent,'Sub-mapa');
});


test('favorites appear first before pagination and update only after the server confirms', async () => {
  const rows = [], fields = new Map(), requests = [];
  const $ = id => {
    if (!fields.has(id)) fields.set(id, {value:'',replaceChildren(){rows.length=0;},
      insertRow(){const cells=[]; rows.push(cells); return {insertCell(){const cell={append(node){this.child=node;}};cells.push(cell);return cell;}};}});
    return fields.get(id);
  };
  const maps = Array.from({length:12}, (_, i) => ({id:`m${i}`,updatedAt:100-i,layout:{name:`Map ${i}`,nodes:[],links:[]}}));
  const makeElement = () => ({attrs:{},setAttribute(k,v){this.attrs[k]=v;},addEventListener(k,fn){this[k]=fn;},append(){},focus(){this.focused=true;}});
  let reject = false;
  const context = vm.createContext({$,maps,page:0,pageSize:10,favoriteMaps:['m11'],favoriteSaving:false,
    svg:makeElement,document:{createElement:makeElement},window:{notify(){}},
    api:async (path, body)=>{requests.push([path,body.favorite]); if(reject) throw Error('offline'); return body.favorite?['m3','m11']:['m11'];}});
  vm.runInContext(source.slice(source.indexOf('async function toggleMapFavorite('),source.indexOf("$('map-search').oninput")),context);
  vm.runInContext('renderList()',context);
  assert.equal(rows[0][1].child.textContent,'Map 11');
  assert.equal(rows[0][0].child.attrs['aria-pressed'],'true');
  await vm.runInContext('toggleMapFavorite(maps[3])',context);
  assert.equal(rows[0][1].child.textContent,'Map 3');
  assert.equal(rows[1][1].child.textContent,'Map 11');
  assert.equal(rows[0][0].child.focused,true);
  await vm.runInContext('toggleMapFavorite(maps[3])',context);
  assert.equal(rows[0][1].child.textContent,'Map 11');
  assert.deepEqual(requests,[['/favorites/m3',true],['/favorites/m3',false]]);
  reject = true;
  await vm.runInContext('toggleMapFavorite(maps[0])',context);
  assert.equal(rows[0][1].child.textContent,'Map 11');
  assert.equal(rows[1][0].child.attrs['aria-pressed'],'false');
  $('map-search').value='Map 3';
  vm.runInContext('renderList()',context);
  assert.equal(rows.length,1);
  assert.equal(rows[0][1].child.textContent,'Map 3');
});
