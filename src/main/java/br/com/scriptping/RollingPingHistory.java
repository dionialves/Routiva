package br.com.scriptping;

/** Compact, ordered primitive buffer: about 25 bytes per sample, excluding spare capacity. */
public class RollingPingHistory {

    private long[] ids = new long[64],
        times = new long[64];
    private double[] latencies = new double[64];
    private byte[] outcomes = new byte[64];
    private int size, head;
    private long maxId, version;
    private PingSample lastKnown;

    int index(int offset) {
        return (head + offset) % ids.length;
    }

    public int size() {
        return size;
    }

    public long time(int i) {
        return times[index(i)];
    }

    public byte outcome(int i) {
        return outcomes[index(i)];
    }

    public double latency(int i) {
        return latencies[index(i)];
    }

    public long version() {
        return version;
    }

    public PingSample lastKnown() {
        return lastKnown;
    }

    public PingSample lastAt(long now) {
        if (lastKnown != null && lastKnown.measuredAt() <= now) return lastKnown;
        for (int i = size - 1; i >= 0; i--) if (time(i) <= now) {
            int p = index(i);
            return new PingSample(
                ids[p],
                lastKnown.hostId(),
                lastKnown.target(),
                times[p],
                outcomes[p] == PingSample.SUCCESS ? latencies[p] : null,
                outcomes[p] == PingSample.LOSS
                    ? "Tempo esgotado"
                    : outcomes[p] == PingSample.ERROR
                      ? "Falha ao executar ping"
                      : null
            );
        }
        return null;
    }

    public boolean add(PingSample sample) {
        if (sample.id() <= maxId) for (int i = 0; i < size; i++) if (
            ids[index(i)] == sample.id()
        ) return false;
        if (size == ids.length) {
            int capacity = ids.length * 2;
            long[] newIds = new long[capacity],
                newTimes = new long[capacity];
            double[] newLatency = new double[capacity];
            byte[] newOutcomes = new byte[capacity];
            for (int i = 0; i < size; i++) {
                int p = index(i);
                newIds[i] = ids[p];
                newTimes[i] = times[p];
                newLatency[i] = latencies[p];
                newOutcomes[i] = outcomes[p];
            }
            ids = newIds;
            times = newTimes;
            latencies = newLatency;
            outcomes = newOutcomes;
            head = 0;
        }
        int position = size;
        while (
            position > 0 &&
            (time(position - 1) > sample.measuredAt() ||
                (time(position - 1) == sample.measuredAt() &&
                    ids[index(position - 1)] > sample.id()))
        ) {
            int from = index(position - 1),
                to = index(position);
            ids[to] = ids[from];
            times[to] = times[from];
            latencies[to] = latencies[from];
            outcomes[to] = outcomes[from];
            position--;
        }
        int p = index(position);
        ids[p] = sample.id();
        times[p] = sample.measuredAt();
        latencies[p] = sample.latency() == null ? Double.NaN : sample.latency();
        outcomes[p] = sample.outcome();
        size++;
        maxId = Math.max(maxId, sample.id());
        version++;
        if (
            lastKnown == null ||
            sample.measuredAt() > lastKnown.measuredAt() ||
            (sample.measuredAt() == lastKnown.measuredAt() && sample.id() > lastKnown.id())
        ) lastKnown = sample;
        return true;
    }

    public void prune(long cutoff) {
        while (size > 0 && time(0) < cutoff) {
            head = (head + 1) % ids.length;
            size--;
            version++;
        }
    }
}
