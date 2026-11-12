/* RouterOS counters are cumulative bytes; derive rates from consecutive observations. */
(function (root) {
  'use strict';
  function rate(previous, current, elapsedMillis) {
    if (elapsedMillis <= 0 || elapsedMillis > 15000 || !Number.isFinite(elapsedMillis)) return null;
    if (!/^\d+$/.test(String(previous)) || !/^\d+$/.test(String(current))) return null;
    const delta = BigInt(current) - BigInt(previous);
    if (delta < 0n) return null;
    const bitsPerSecond = Number(delta) * 8000 / elapsedMillis;
    return Number.isFinite(bitsPerSecond) ? bitsPerSecond : null;
  }
  function format(bitsPerSecond) {
    if (bitsPerSecond == null || !Number.isFinite(bitsPerSecond) || bitsPerSecond < 0) return 'Aguardando…';
    const mega = bitsPerSecond > 2000000;
    return (bitsPerSecond / (mega ? 1000000 : 1000)).toLocaleString('pt-BR', {
      maximumFractionDigits:2,
    }) + (mega ? ' Mbps' : ' kbps');
  }
  function wirelessCounters(client) {
    const pair = String(client.bytes ?? '').split(',').map(value => value.trim());
    return { tx:client['tx-bytes'] ?? pair[0], rx:client['rx-bytes'] ?? pair[1] };
  }
  function wirelessRates(previous, client, at) {
    const counters = wirelessCounters(client);
    const same = previous && previous.id === client['.id'];
    const direct = key => {
      const value = client[key];
      return value != null && /^\d+$/.test(String(value)) ? Number(value) : null;
    };
    return {
      download:direct('tx-bits-per-second') ?? (same ? rate(previous.tx, counters.tx, at - previous.at) : null),
      upload:direct('rx-bits-per-second') ?? (same ? rate(previous.rx, counters.rx, at - previous.at) : null),
      baseline:{ ...counters, at, id:client['.id'] },
    };
  }
  const api = { rate, format, wirelessCounters, wirelessRates };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.HostTraffic = api;
})(typeof window !== 'undefined' ? window : globalThis);
