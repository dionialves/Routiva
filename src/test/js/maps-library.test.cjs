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
  const context = vm.createContext({$,maps,page:0,pageSize:10,document:{createElement(){return {setAttribute(){},addEventListener(){}};}}});
  vm.runInContext(source.slice(source.indexOf('function renderList()'),source.indexOf("$('map-search').oninput")),context);
  vm.runInContext('renderList()',context);
  assert.equal(rows.length,3);
  assert.ok(rows.every(row=>row.length===5));
  assert.deepEqual(rows.map(row=>row[4].textContent),['Principal','Sub-mapa','Principal']);
  $('map-search').value='Filial';
  vm.runInContext('renderList()',context);
  assert.equal(rows.length,1);
  assert.equal(rows[0][4].textContent,'Sub-mapa');
});
