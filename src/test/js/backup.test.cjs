const { install: installPagination } = require('./support/table-pagination.cjs');
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/backup.js'), 'utf8');

test('backup history initializes its filters and renders saved entries', async () => {
  const fields = new Map();
  const get = id => {
    if (!fields.has(id)) {
      fields.set(id, {
        replaceChildren(...children) { this.children = children; },
        get selectedOptions() { return (this.children ?? []).filter(option => option.selected); },
        insertRow() {
          const cells = [];
          this.children.push(cells);
          return {
            append(heading) { cells.push(heading); },
            insertCell() { const cell = {}; cells.push(cell); return cell; },
          };
        },
      });
    }
    return fields.get(id);
  };
  const context = vm.createContext({
    $: get,
    Node: class {},
    Option: class {
      constructor(text, value, defaultSelected, selected) {
        Object.assign(this, { text, value, selected });
      }
    },
    document: { createElement: () => ({ append(child) { this.child = child; } }) },
    window: { setupMultiPicker: () => Object.assign(() => {}, { dispose() {} }) },
    downloadUrl: entry => `/api/backups/${entry.id}/download`,
    date: { format: timestamp => String(timestamp) },
    request: async (route) => ({
      json: async () => route === '/hosts'
        ? [{ id: '1', name: 'Roteador', groups: [{ id: '10', name: 'MikroTik' }] }]
        : [{ id: 'backup-1', hostId: '1', hostName: 'Roteador', filename: 'router.rsc' }],
    }),
  });
  const pagers = {};
  installPagination(context, pagers, {'backup-pagination': ['registros', null, 'backup-count']});
  vm.runInContext(
    'let entries, historyInventory, page = 0; const filterPickers = {}, pageSize = 50;' +
    source.slice(source.indexOf('  function cell('), source.indexOf('  async function openPreview(')),
    context,
  );
  await vm.runInContext('loadEntries()', context);

  assert.equal(get('backup-rows').children[0][0].child.textContent, 'Roteador');
  assert.equal(get('backup-rows').children[0][3].textContent, 'router.rsc');
  assert.equal(pagers['backup-count'].textContent, 'Exibindo 1–1 de 1 registros');
});
