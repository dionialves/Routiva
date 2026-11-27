const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../../main/resources/static/table-pagination.js'), 'utf8');

const sortSource = fs.readFileSync(path.join(__dirname, '../../../main/resources/static/table-sort.js'), 'utf8');

function pager(unit = 'registros') {
  const children = Array.from({ length: 10 }, () => ({
    attributes: {},
    setAttribute(name, value) { this.attributes[name] = value; },
    removeAttribute(name) { delete this.attributes[name]; },
    emit() { return this.onclick?.(); },
    fire() { return this.onclick?.(); },
    handlers: { click() { return this.button.onclick?.(); } },
  }));
  for (const button of children) button.handlers.button = button;
  return {
    children,
    dataset: { paginationUnit: unit },
    classList: { add() {} },
    setAttribute() {},
    querySelector() { return null; },
  };
}

function install(context, elements = {}, definitions = {}) {
  for (const [id, [unit, previous, status, next]] of Object.entries(definitions)) {
    const nav = pager(unit);
    elements[id] = nav;
    if (previous) elements[previous] = nav.children[2];
    if (status) elements[status] = nav.children[0];
    if (next) elements[next] = nav.children[8];
  }
  context.document ??= {};
  context.document.getElementById ??= id => elements[id];
  const sandbox = vm.isContext(context) ? context : vm.createContext(context);
  vm.runInContext(source, sandbox);
  vm.runInContext(sortSource, sandbox);
}

module.exports = { install, pager };
