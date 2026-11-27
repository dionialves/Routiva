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

test('backup deletion expires, blocks duplicate requests and recovers after failure', async () => {
  const notices = [];
  const label = {};
  const icon = { setAttribute(name, value) { this[name] = value; } };
  const deleteButton = {
    classList: new Set(),
    querySelector: selector => selector === 'span' ? label : icon,
    addEventListener() {},
    focus() {},
  };
  deleteButton.classList.remove = name => deleteButton.classList.delete(name);
  const copyButton = {};
  const downloadButton = {};
  const closeButton = {};
  const textDialog = {
    open: true,
    addEventListener() {},
    close() { this.open = false; },
  };
  let now = 0;
  let timer;
  let requests = 0;
  let refreshes = 0;
  let complete;
  const context = vm.createContext({
    deleteButton, copyButton, textDialog,
    previewButtons: [deleteButton, copyButton, downloadButton, closeButton],
    $: () => downloadButton,
    window: { notify: (...args) => notices.push(args) },
    performance: { now: () => now },
    setTimeout(callback, delay) { assert.equal(delay, 5000); timer = callback; return 1; },
    clearTimeout() { timer = undefined; },
    loading(button, busy) { button.busy = busy; },
    request(path, options) {
      assert.equal(path, '/backup-1');
      assert.equal(options.method, 'DELETE');
      requests++;
      return new Promise((resolve, reject) => { complete = { resolve, reject }; });
    },
    async loadEntries() { refreshes++; },
  });
  vm.runInContext(
    "let deleting = false, copying = false, confirmUntil = 0, deleteTimer; const preview = { entry: { id: 'backup-1' }, content: '' };" +
    source.slice(source.indexOf('  function updatePreviewButtons'), source.indexOf('  async function openPreview')) +
    source.slice(source.indexOf("  deleteButton.addEventListener('keydown'"), source.lastIndexOf('  loadEntries().catch')),
    context,
  );

  await deleteButton.onclick();
  assert.equal(label.textContent, 'Confirmar exclusão?');
  assert.equal(requests, 0);
  now = 5000;
  timer();
  assert.equal(label.textContent, 'Excluir');
  await deleteButton.onclick();
  assert.equal(requests, 0);

  const failed = deleteButton.onclick();
  assert.equal(deleteButton.busy, true);
  assert.ok(context.previewButtons.every(button => button.disabled));
  await deleteButton.onclick();
  assert.equal(requests, 1);
  complete.reject(new Error('Falha ao excluir'));
  await failed;
  assert.equal(textDialog.open, true);
  assert.ok(context.previewButtons.every(button => !button.disabled));
  assert.equal(label.textContent, 'Excluir');
  assert.deepEqual(notices, [['Falha ao excluir', 'error']]);

  await deleteButton.onclick();
  const succeeded = deleteButton.onclick();
  complete.resolve();
  await succeeded;
  assert.equal(textDialog.open, false);
  assert.equal(refreshes, 1);
  assert.deepEqual(notices.at(-1), ['Backup excluído.']);
});
