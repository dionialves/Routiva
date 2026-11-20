'use strict';

const MapLinks = (() => {
  function points(link, start, end) {
    return [start, ...(link.style === 'corners' ? link.points || [] : []), end];
  }
  function corner(route, index, limit = 18) {
    const a = route[index - 1], point = route[index], c = route[index + 1];
    const before = Math.hypot(point.x - a.x, point.y - a.y), after = Math.hypot(c.x - point.x, c.y - point.y);
    const radius = Math.min(limit, before / 2, after / 2);
    if (!radius) return {point, entry: point, exit: point, radius};
    return {point, radius,
      entry: {x: point.x + (a.x - point.x) * radius / before, y: point.y + (a.y - point.y) * radius / before},
      exit: {x: point.x + (c.x - point.x) * radius / after, y: point.y + (c.y - point.y) * radius / after}};
  }
  function bendPath(link, start, end, index, scale = 1) {
    const route = points(link, start, end);
    const curved = link.lineStyle === 'curved';
    const {entry, point, exit} = corner(route, index + 1, curved ? 18 : 10 / scale);
    return `M ${entry.x} ${entry.y}` + (curved ? ` Q ${point.x} ${point.y} ${exit.x} ${exit.y}`
      : ` L ${point.x} ${point.y} L ${exit.x} ${exit.y}`);
  }
  function path(link, start, end) {
    const route = points(link, start, end);
    const move = `M ${start.x} ${start.y}`;
    if (link.lineStyle !== 'curved') return move + route.slice(1).map(p => ` L ${p.x} ${p.y}`).join('');
    if (route.length === 2) {
      const x = (start.x + end.x) / 2;
      return `${move} C ${x} ${start.y} ${x} ${end.y} ${end.x} ${end.y}`;
    }
    let result = move;
    for (let i = 1; i < route.length - 1; i++) {
      const {entry, point, exit, radius} = corner(route, i);
      if (!radius) { result += ` L ${point.x} ${point.y}`; continue; }
      result += ` L ${entry.x} ${entry.y} Q ${point.x} ${point.y} ${exit.x} ${exit.y}`;
    }
    return result + ` L ${end.x} ${end.y}`;
  }
  function initial(start, end) {
    const y = Math.max(start.y, end.y) + 80;
    return [{x: start.x, y}, {x: end.x, y}];
  }
  function insertion(route, point) {
    let best = {index: 0, distance: Infinity};
    for (let index = 0; index < route.length - 1; index++) {
      const a = route[index], b = route[index + 1];
      const dx = b.x - a.x, dy = b.y - a.y;
      const t = Math.max(0, Math.min(1, ((point.x - a.x) * dx + (point.y - a.y) * dy) / (dx * dx + dy * dy || 1)));
      const distance = Math.hypot(point.x - a.x - t * dx, point.y - a.y - t * dy);
      if (distance < best.distance) best = {index, distance};
    }
    return best.index;
  }
  function midpoint(route) {
    let longest = -1, point;
    for (let i = 0; i < route.length - 1; i++) {
      const a = route[i], b = route[i + 1], length = Math.hypot(b.x - a.x, b.y - a.y);
      if (length > longest) { longest = length; point = {x: (a.x + b.x) / 2, y: (a.y + b.y) / 2}; }
    }
    return point;
  }
  function snap(route, index, point, scale, otherRoutes = []) {
    const neighbors = [route[index - 1], route[index + 1]].filter(Boolean);
    const position = {x: Math.round(point.x), y: Math.round(point.y)};
    const guides = [];
    for (const axis of ['x', 'y']) {
      // Only nearby segments with overlapping spans attract the edited corner.
      // This avoids pulling it toward unrelated lines elsewhere on the map.
      const along = axis === 'x' ? 'y' : 'x';
      const candidates = [];
      for (const other of otherRoutes) {
        for (let i = 1; i < other.length; i++) {
          const a = other[i - 1], b = other[i];
          if (a[axis] !== b[axis] || a[along] === b[along]) continue;
          const min = Math.min(a[along], b[along]), max = Math.max(a[along], b[along]);
          const overlaps = neighbors.some(neighbor =>
            Math.max(point[along], neighbor[along]) >= min - 8 / scale &&
            Math.min(point[along], neighbor[along]) <= max + 8 / scale);
          if (overlaps && Math.abs(a[axis] - point[axis]) <= 8 / scale) {
            candidates.push({[axis]: a[axis], [along]: Math.max(min, Math.min(max, point[along]))});
          }
        }
      }
      const targets = candidates.length ? candidates : neighbors;
      const closest = targets.reduce((best, neighbor) =>
        Math.abs(neighbor[axis] - point[axis]) <= (candidates.length ? 8 : 6) / scale &&
        (!best || Math.abs(neighbor[axis] - point[axis]) < Math.abs(best[axis] - point[axis]))
          ? neighbor : best, null);
      if (!closest) continue;
      position[axis] = closest[axis];
      guides.push(axis === 'x'
        ? {x1: closest.x, y1: Math.min(position.y, closest.y) - 12 / scale,
          x2: closest.x, y2: Math.max(position.y, closest.y) + 12 / scale}
        : {x1: Math.min(position.x, closest.x) - 12 / scale, y1: closest.y,
          x2: Math.max(position.x, closest.x) + 12 / scale, y2: closest.y});
    }
    return {position, guides};
  }
  return {points, path, bendPath, initial, insertion, midpoint, snap};
})();
if (typeof module !== 'undefined') module.exports = MapLinks;
