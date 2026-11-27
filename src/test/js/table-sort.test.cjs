const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('src/main/resources/static/table-sort.js', 'utf8');
const sort = vm.runInNewContext(source + '\nTableSort.sort');

function order(rows, direction) {
  return Array.from(sort(rows, { column: 0, direction }, [row => row]));
}

test('sorts dates and mixed traffic units by value and keeps missing readings last', () => {
  assert.deepEqual(order(['01/12/26, 08:00', '30/11/26 12:00', '—'], 'ascending'),
    ['30/11/26 12:00', '01/12/26, 08:00', '—']);
  assert.deepEqual(order(['800 Mbps', '—', '1,25 Gbps', '2 kbps'], 'descending'),
    ['1,25 Gbps', '800 Mbps', '2 kbps', '—']);
});

test('uses natural ordering for host names and keeps equal records in source order', () => {
  assert.deepEqual(order(['Host 10', 'Host 2', 'Hóst 2', 'Host 1'], 'ascending'),
    ['Host 1', 'Host 2', 'Hóst 2', 'Host 10']);
});
