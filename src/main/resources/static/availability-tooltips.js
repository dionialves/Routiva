/* Native popovers use the top layer, outside card/ancestor overflow clipping. */
(() => {
  for (const trigger of document.querySelectorAll('.metric-details-trigger')) {
    const tooltip = document.getElementById(trigger.getAttribute('aria-controls'));
    let closeTimer, pinned = false;
    const isOpen = () => tooltip.matches(':popover-open');
    function position() {
      const anchor = trigger.getBoundingClientRect();
      const bounds = tooltip.getBoundingClientRect();
      const viewport = window.visualViewport;
      const left = viewport?.offsetLeft || 0, top = viewport?.offsetTop || 0;
      const width = viewport?.width || window.innerWidth, height = viewport?.height || window.innerHeight;
      tooltip.style.left = Math.max(left + 8, Math.min(anchor.left, left + width - bounds.width - 8)) + 'px';
      const below = anchor.bottom + bounds.height <= top + height - 8;
      tooltip.style.top = Math.max(top + 8, Math.min(below ? anchor.bottom : anchor.top - bounds.height, top + height - bounds.height - 8)) + 'px';
    }
    function open() {
      clearTimeout(closeTimer);
      if (!isOpen()) tooltip.showPopover();
      position();
    }
    function close() {
      clearTimeout(closeTimer);
      if (isOpen()) tooltip.hidePopover();
    }
    function leave() {
      if (!pinned && !trigger.matches(':focus-visible')) closeTimer = setTimeout(close, 120);
    }
    trigger.addEventListener('pointerenter', event => { if (event.pointerType !== 'touch') open(); });
    trigger.addEventListener('pointerleave', leave);
    trigger.addEventListener('focus', open);
    trigger.addEventListener('blur', () => { if (!pinned) close(); });
    trigger.addEventListener('click', event => { event.preventDefault(); pinned = !pinned; if (pinned) open(); else close(); });
    tooltip.addEventListener('pointerenter', () => clearTimeout(closeTimer));
    tooltip.addEventListener('pointerleave', leave);
    tooltip.addEventListener('toggle', () => {
      const expanded = isOpen();
      trigger.setAttribute('aria-expanded', String(expanded));
      if (!expanded) { pinned = false; clearTimeout(closeTimer); }
    });
    // Native auto popovers dismiss on outside taps and Escape.
    window.addEventListener('resize', () => { if (isOpen()) position(); });
    window.addEventListener('scroll', event => { if (isOpen() && !tooltip.contains(event.target)) close(); }, true);
    window.visualViewport?.addEventListener('resize', () => { if (isOpen()) position(); });
  }
})();
