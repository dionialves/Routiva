'use strict';

// Selection chrome stays in screen coordinates; equipment geometry remains in MapSwitch.
const MapElementControls = (() => {
  const definitions = [
    {action: 'rotation', label: 'Girar elemento', path: 'M20 7v5h-5M20 12a8 8 0 1 1-2.34-5.66L20 9'},
    {action: 'scale', label: 'Redimensionar elemento', path: 'M14 4h6v6m0-6-6 6M10 20H4v-6m0 6 6-6'},
    {action: 'flipX', label: 'Inverter horizontalmente', path: 'M3 12h18M7 8l-4 4 4 4m10-8 4 4-4 4'},
    {action: 'reset', label: 'Restaurar tamanho e orientação', path: 'M8 8h8v8H8zM12 2v4m-2-2 2 2 2-2M12 22v-4m-2 2 2-2 2 2M2 12h4m-2-2 2 2-2 2M22 12h-4m2-2-2 2 2 2'}
  ];
  let timer, tooltip;
  function hideTooltip() {
    clearTimeout(timer);
    tooltip?.remove(); tooltip = null;
  }
  function showTooltip(handle, label, canvas) {
    hideTooltip();
    timer = setTimeout(() => {
      if (!handle.isConnected) return;
      tooltip = document.createElement('div');
      tooltip.className = 'map-control-tooltip'; tooltip.setAttribute('role', 'tooltip');
      tooltip.textContent = label; document.body.append(tooltip);
      const anchor = handle.getBoundingClientRect(), bounds = canvas.getBoundingClientRect();
      const left = Math.max(4, bounds.left), right = Math.min(window.innerWidth - 4, bounds.right);
      const top = Math.max(4, bounds.top), bottom = Math.min(window.innerHeight - 4, bounds.bottom);
      tooltip.style.maxWidth = Math.max(24, right - left - 18) + 'px';
      const width = tooltip.offsetWidth, height = tooltip.offsetHeight;
      tooltip.style.left = Math.max(left, Math.min(right - width, anchor.left + anchor.width / 2 - width / 2)) + 'px';
      tooltip.style.top = Math.max(top, Math.min(bottom - height,
        anchor.top - height - 8 >= top ? anchor.top - height - 8 : anchor.bottom + 8)) + 'px';
    }, 300);
  }
  function positions(box) {
    return {rotation: {x: (box.left + box.right) / 2, y: box.top},
      scale: {x: box.right, y: box.top}, flipX: {x: box.right, y: (box.top + box.bottom) / 2},
      reset: {x: box.right, y: box.bottom}};
  }
  function draw({canvas, node, box, scale, active, svg, onStart, onAction, onAdjust}) {
    const unit = 1 / scale, points = positions(box);
    canvas.append(svg('rect', {x: node.x + box.left, y: node.y + box.top,
      width: box.right - box.left, height: box.bottom - box.top,
      rx: 6 * unit, class: 'map-selection-border', 'vector-effect': 'non-scaling-stroke', 'aria-hidden': 'true'}));
    for (const control of definitions) {
      const point = points[control.action], instant = ['flipX', 'reset'].includes(control.action);
      const handle = svg('g', {class: 'map-transform-handle' + (active === control.action ? ' is-active' : ''),
        tabindex: 0, role: 'button', 'data-transform': control.action, 'aria-label': control.label,
        ...(control.action === 'flipX' ? {'aria-pressed': String(!!node.flipX)} : {}),
        transform: `translate(${node.x + point.x} ${node.y + point.y}) scale(${unit})`});
      handle.append(svg('circle', {r: 12}));
      const icon = svg('svg', {x: -7, y: -7, width: 14, height: 14, viewBox: '0 0 24 24',
        fill: 'none', stroke: 'currentColor', 'stroke-width': control.action === 'reset' ? 1.6 : 1.8,
        'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true'});
      icon.append(svg('path', {d: control.path})); handle.append(icon);
      handle.onpointerdown = event => {
        if (event.button !== 0) return;
        event.stopPropagation(); event.preventDefault(); hideTooltip();
        if (!instant) onStart(control.action, event);
      };
      handle.onclick = event => {
        event.stopPropagation(); hideTooltip();
        if (instant) onAction(control.action);
      };
      handle.onkeydown = event => {
        if (instant && ['Enter', ' '].includes(event.key)) {
          event.preventDefault(); event.stopPropagation(); hideTooltip(); onAction(control.action);
        } else if (!instant && ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) {
          event.preventDefault(); event.stopPropagation(); hideTooltip();
          onAdjust(control.action, ['ArrowLeft', 'ArrowDown'].includes(event.key) ? -1 : 1, event.shiftKey);
        }
      };
      handle.onpointerenter = handle.onfocus = () => { if (!active) showTooltip(handle, control.label, canvas); };
      handle.onpointerleave = handle.onblur = hideTooltip;
      canvas.append(handle);
    }
    if (active === 'scale') {
      const badge = svg('g', {class: 'map-scale-indicator', 'pointer-events': 'none',
        transform: `translate(${node.x + (box.left + box.right) / 2} ${node.y + box.top - 30 * unit}) scale(${unit})`});
      badge.append(svg('rect', {x: -30, y: -12, width: 60, height: 24, rx: 5}));
      const label = svg('text', {x: 0, y: 0, 'text-anchor': 'middle', 'dominant-baseline': 'central'});
      label.textContent = Math.round((node.scale || 1) * 100) + '%';
      badge.append(label);
      canvas.append(badge);
    }
  }
  return {draw, positions, hideTooltip};
})();
if (typeof module !== 'undefined') module.exports = MapElementControls;
