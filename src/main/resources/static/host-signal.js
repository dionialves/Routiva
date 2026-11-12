/* Present the original server-side connection diagnosis without recalculating thresholds. */
((root) => {
  'use strict';
  const names = ['Normal', 'Atenção', 'Ruim', 'Crítico'];
  const colors = ['#22c55e', '#fbbf24', '#fb923c', '#f87171'];
  function policy() {
    const signal = client => {
      const raw = client['avg-signal'];
      const value = raw == null || raw === '' ? NaN : Number(raw);
      return Number.isFinite(value) ? value : null;
    };
    const category = client => {
      const raw = client['quality-severity'];
      const value = raw == null || raw === '' ? -1 : Number(raw);
      return Number.isInteger(value) && value >= 0 && value <= 3 ? value : -1;
    };
    const summarize = clients => {
      const active = clients.filter(c => c['connection-status'] !== 'disconnected');
      const counts = [0, 0, 0, 0]; let missing = 0;
      active.forEach(c => { const index = category(c); if (index < 0) missing++; else counts[index]++; });
      return { total:active.length, valid:active.length - missing, missing, counts };
    };
    return { signal, category, summarize, names, colors };
  }
  root.HostSignal = { policy };
  if (typeof module !== 'undefined') module.exports = root.HostSignal;
})(typeof window === 'undefined' ? globalThis : window);
