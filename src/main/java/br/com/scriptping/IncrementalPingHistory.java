package br.com.scriptping;

import java.util.*;

/** Ordered compact blocks. Only changed blocks inspect raw pings; windows reuse their totals. */
final class IncrementalPingHistory {
    private static final int BLOCK_SIZE = 256;
    private record Key(long time, long id) implements Comparable<Key> {
        public int compareTo(Key other) {
            int order = Long.compare(time, other.time);
            return order != 0 ? order : Long.compare(id, other.id);
        }
    }

    static final class Totals {
        long all, successes, losses, coverage, firstTime, lastTime;
        double sum;
        byte firstOutcome, lastOutcome;
        int prefixLoss, suffixLoss, maxLoss;

        Double mean() { return successes == 0 ? null : sum / successes; }
        long attempts() { return successes + losses; }

        void append(Totals right, long interval, long maxGap) {
            if (right.all == 0) return;
            if (all == 0) {
                firstTime = right.firstTime;
                firstOutcome = right.firstOutcome;
                prefixLoss = right.prefixLoss;
            } else {
                if (right.firstOutcome != PingSample.ERROR && lastOutcome != PingSample.ERROR) {
                    long gap = right.firstTime - lastTime;
                    coverage += (gap >= 0 && gap <= maxGap ? gap : interval) - interval;
                }
                if (prefixLoss == all) prefixLoss += right.prefixLoss;
            }
            maxLoss = Math.max(Math.max(maxLoss, right.maxLoss), suffixLoss + right.prefixLoss);
            suffixLoss = right.suffixLoss == right.all ? suffixLoss + right.suffixLoss : right.suffixLoss;
            all += right.all;
            successes += right.successes;
            losses += right.losses;
            sum += right.sum;
            coverage += right.coverage;
            lastTime = right.lastTime;
            lastOutcome = right.lastOutcome;
        }

        void add(long time, byte outcome, double latency, long interval, long maxGap) {
            if (all == 0) { firstTime = time; firstOutcome = outcome; }
            if (outcome != PingSample.ERROR) {
                long gap = all == 0 || lastOutcome == PingSample.ERROR ? interval : time - lastTime;
                coverage += gap >= 0 && gap <= maxGap ? gap : interval;
                if (outcome == PingSample.LOSS) losses++;
                else { successes++; sum += latency; }
            }
            if (outcome == PingSample.LOSS) {
                if (prefixLoss == all) prefixLoss++;
                suffixLoss++;
                maxLoss = Math.max(maxLoss, suffixLoss);
            } else suffixLoss = 0;
            all++;
            lastTime = time;
            lastOutcome = outcome;
        }
    }

    private static final class Block {
        private final long interval, maxGap;
        Block(long interval, long maxGap) { this.interval = interval; this.maxGap = maxGap; }
        final long[] ids = new long[BLOCK_SIZE + 1], times = new long[BLOCK_SIZE + 1];
        final double[] latencies = new double[BLOCK_SIZE + 1];
        final byte[] outcomes = new byte[BLOCK_SIZE + 1];
        int size;
        volatile byte[] checkpoint;
        boolean shared;
        Totals totals = new Totals();
        Key key() { return new Key(times[0], ids[0]); }
        void recompute() { checkpoint = null; totals = range(0, size); }
        Totals range(int from, int to) {
            Totals result = new Totals();
            for (int i = from; i < to; i++) result.add(times[i], outcomes[i], latencies[i], interval, maxGap);
            return result;
        }
        void copy(int from, Block target, int count) {
            System.arraycopy(ids, from, target.ids, 0, count);
            System.arraycopy(times, from, target.times, 0, count);
            System.arraycopy(latencies, from, target.latencies, 0, count);
            System.arraycopy(outcomes, from, target.outcomes, 0, count);
            target.size = count;
        }
        void insert(PingSample sample) {
            checkpoint = null;
            int pos = size;
            Key key = new Key(sample.measuredAt(), sample.id());
            while (pos > 0 && new Key(times[pos - 1], ids[pos - 1]).compareTo(key) > 0) pos--;
            if (pos > 0 && ids[pos - 1] == sample.id()) return;
            System.arraycopy(ids, pos, ids, pos + 1, size - pos);
            System.arraycopy(times, pos, times, pos + 1, size - pos);
            System.arraycopy(latencies, pos, latencies, pos + 1, size - pos);
            System.arraycopy(outcomes, pos, outcomes, pos + 1, size - pos);
            ids[pos] = sample.id(); times[pos] = sample.measuredAt();
            latencies[pos] = sample.latency() == null ? Double.NaN : sample.latency();
            outcomes[pos] = sample.outcome();
            size++;
            if (pos == size - 1) totals.add(times[pos], outcomes[pos], latencies[pos], interval, maxGap);
            else recompute();
        }
    }

    private final NavigableMap<Key, Block> blocks = new TreeMap<>();
    private final long interval, maxGap;
    private PingSample last;
    private long retained;

    List<java.util.function.Supplier<byte[]>> checkpointBlocks() {
        List<java.util.function.Supplier<byte[]>> result = new ArrayList<>();
        for (Block b : blocks.values()) {
            b.shared = true;
            result.add(() -> {
                if (b.checkpoint == null) b.checkpoint = CheckpointCodec.encode(out -> {
                    out.writeInt(b.size);
                    for (int i = 0; i < b.size; i++) {
                        out.writeLong(b.ids[i]); out.writeLong(b.times[i]);
                        out.writeDouble(b.latencies[i]); out.writeByte(b.outcomes[i]);
                    }
                });
                return b.checkpoint;
            });
        }
        return result;
    }

    void restoreBlock(byte[] payload, String host, String target) {
        Block b = new Block(interval, maxGap);
        CheckpointCodec.decode(payload, in -> {
            b.size = CheckpointCodec.count(in, BLOCK_SIZE);
            if (b.size == 0) throw new java.io.IOException("Empty block");
            for (int i = 0; i < b.size; i++) {
                b.ids[i] = in.readLong(); b.times[i] = in.readLong();
                b.latencies[i] = in.readDouble(); b.outcomes[i] = in.readByte();
                if (b.outcomes[i] < 0 || b.outcomes[i] > 2) throw new java.io.IOException("Invalid outcome");
            }
        });
        b.recompute(); b.checkpoint = payload;
        if (blocks.putIfAbsent(b.key(), b) != null) throw new IllegalArgumentException("Duplicate block");
        retained += b.size;
        int i = b.size - 1;
        var sample = new PingSample(b.ids[i], host, target, b.times[i],
            b.outcomes[i] == PingSample.SUCCESS ? b.latencies[i] : null,
            b.outcomes[i] == PingSample.LOSS ? "Tempo esgotado" : "internal", 20);
        if (last == null || new Key(sample.measuredAt(), sample.id()).compareTo(new Key(last.measuredAt(), last.id())) > 0) last = sample;
    }

    // Copy only indexes and counters; raw blocks are copied on first mutation.
    IncrementalPingHistory fork() {
        var copy = new IncrementalPingHistory(interval, maxGap);
        blocks.forEach((key, block) -> { block.shared = true; copy.blocks.put(key, block); });
        copy.last = last; copy.retained = retained; copy.evaluatedAt = evaluatedAt;
        counters.forEach((duration, total) -> {
            var value = new Totals(); value.append(total, interval, maxGap);
            copy.counters.put(duration, value);
        });
        return copy;
    }

    long size() { return retained; }
    private long evaluatedAt = Long.MIN_VALUE;
    private final Map<Long, Totals> counters = new HashMap<>();

    // Advance before pruning: subtract exactly the contributions leaving each window.
    void advance(long now, long... durations) {
        for (long duration : durations) {
            Totals total = counters.get(duration);
            if (total == null) {
                counters.put(duration, window(now - duration, now));
            } else if (now > evaluatedAt) {
                Totals expired = window(evaluatedAt - duration, Math.min(now - duration, evaluatedAt));
                total.all -= expired.all;
                total.successes -= expired.successes;
                total.losses -= expired.losses;
                total.sum -= expired.sum;
                if (total.successes == 0) total.sum = 0;
            }
        }
        evaluatedAt = now;
    }

    Totals metrics(long duration) { return counters.get(duration); }

    int consecutive() {
        int count = 0;
        for (Block block : blocks.descendingMap().values()) {
            count += block.totals.suffixLoss;
            if (block.totals.suffixLoss != block.totals.all) break;
        }
        return count;
    }

    IncrementalPingHistory(long interval, long maxGap) { this.interval = interval; this.maxGap = maxGap; }

    void add(PingSample sample) {
        Key key = new Key(sample.measuredAt(), sample.id());
        var entry = blocks.floorEntry(key);
        Block block;
        if (entry == null) {
            if (blocks.isEmpty()) block = new Block(interval, maxGap);
            else { block = blocks.pollFirstEntry().getValue(); }
        } else {
            block = entry.getValue();
            blocks.remove(entry.getKey());
        }
        if (block.shared) {
            Block copy = new Block(interval, maxGap); block.copy(0, copy, block.size); copy.recompute(); block = copy;
        }
        int previousSize = block.size;
        block.insert(sample);
        boolean inserted = block.size > previousSize;
        if (inserted) retained++;
        if (block.size > BLOCK_SIZE) {
            Block right = new Block(interval, maxGap);
            int midpoint = block.size / 2;
            block.copy(midpoint, right, block.size - midpoint);
            block.size = midpoint;
            block.recompute(); right.recompute();
            blocks.put(right.key(), right);
        }
        blocks.put(block.key(), block);
        if (inserted) counters.forEach((duration, total) -> {
            if (sample.measuredAt() > evaluatedAt - duration && sample.measuredAt() <= evaluatedAt) {
                total.all++;
                if (sample.outcome() == PingSample.LOSS) total.losses++;
                else if (sample.outcome() == PingSample.SUCCESS) {
                    total.successes++;
                    total.sum += sample.latency();
                }
            }
        });
        if (last == null || key.compareTo(new Key(last.measuredAt(), last.id())) > 0) last = sample;
    }

    void expire(long cutoff) {
        while (!blocks.isEmpty()) {
            Block first = blocks.firstEntry().getValue();
            if (first.times[0] > cutoff) break;
            blocks.pollFirstEntry();
            int pos = 0;
            while (pos < first.size && first.times[pos] <= cutoff) pos++;
            retained -= pos;
            if (pos < first.size) {
                if (first.shared) {
                    Block copy = new Block(interval, maxGap); first.copy(pos, copy, first.size-pos); first = copy;
                } else first.copy(pos, first, first.size - pos);
                first.recompute();
                blocks.put(first.key(), first);
                break;
            }
        }
        if (blocks.isEmpty()) last = null;
    }

    boolean isEmpty() { return blocks.isEmpty(); }
    PingSample last() { return last; }
    void restoreLast(PingSample sample) {
        if (last == null || last.id() != sample.id() || last.measuredAt() != sample.measuredAt())
            throw new IllegalArgumentException("Invalid last sample");
        last = sample;
    }

    Totals window(long start, long end) {
        Totals total = new Totals();
        var first = blocks.floorKey(new Key(start, Long.MAX_VALUE));
        var relevant = first == null ? blocks : blocks.tailMap(first, true);
        for (Block block : relevant.values()) {
            if (block.times[0] > end) break;
            if (block.times[0] > start && block.times[block.size - 1] <= end) {
                total.append(block.totals, interval, maxGap);
            } else {
                int from = 0, to = block.size;
                while (from < to && block.times[from] <= start) from++;
                while (to > from && block.times[to - 1] > end) to--;
                total.append(block.range(from, to), interval, maxGap);
            }
        }
        // Only resets in the first sampling interval can need boundary clipping.
        byte previous = PingSample.ERROR;
        long previousTime = 0;
        outer: for (Block block : relevant.values()) {
            for (int i = 0; i < block.size; i++) {
                long time = block.times[i];
                if (time <= start) continue;
                if (time > end || time - start >= interval) break outer;
                byte outcome = block.outcomes[i];
                if (outcome != PingSample.ERROR) {
                    long gap = previous == PingSample.ERROR ? interval : time - previousTime;
                    if (gap < 0 || gap > maxGap) gap = interval;
                    total.coverage -= Math.max(0, gap - (time - start));
                }
                previous = outcome;
                previousTime = time;
            }
        }
        return total;
    }
}
