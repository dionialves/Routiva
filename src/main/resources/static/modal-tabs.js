(() => {
  // Scroll only the navigation, never the form or the document underneath a dialog.
  function reveal(tab) {
    const list = tab.closest('.analysis-tabs');
    if (!list || tab.getAttribute('aria-selected') !== 'true') return;
    const bounds = list.getBoundingClientRect();
    const item = tab.getBoundingClientRect();
    if (item.left < bounds.left) list.scrollLeft -= bounds.left - item.left;
    else if (item.right > bounds.right) list.scrollLeft += item.right - bounds.right;
  }
  function revealSelected() {
    document.querySelectorAll('.analysis-tabs [role=tab][aria-selected=true]').forEach(reveal);
  }
  new MutationObserver(records => {
    for (const record of records) {
      if (record.attributeName === 'aria-selected') reveal(record.target);
      else if (record.attributeName === 'open') revealSelected();
    }
  }).observe(document.body, {
    subtree: true,
    attributes: true,
    attributeFilter: ['aria-selected', 'open'],
  });
  window.addEventListener('resize', revealSelected);
  revealSelected();
})();
