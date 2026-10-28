package br.com.scriptping;

/**
 * Single-writer backfill cursor. IDs <= boundary belong exclusively to this reader;
 * IDs > boundary belong exclusively to the live reader, even for late measurements.
 * Descending (measured_at, id) seeks prioritize recent data without time-window scans.
 */
final class IndicatorBootstrap {
    private final long boundary;
    private long beforeTime = Long.MAX_VALUE, beforeId = Long.MAX_VALUE;
    private Long loadedFrom;

    IndicatorBootstrap(long boundary) {
        this.boundary = boundary;
    }

    IndicatorBootstrap copy() {
        var copy = new IndicatorBootstrap(boundary);
        copy.beforeTime = beforeTime; copy.beforeId = beforeId; copy.loadedFrom = loadedFrom;
        return copy;
    }

    byte[] checkpoint() {
        return CheckpointCodec.encode(out -> {
            out.writeLong(boundary); out.writeLong(beforeTime); out.writeLong(beforeId);
            out.writeBoolean(loadedFrom != null);
            if (loadedFrom != null) out.writeLong(loadedFrom);
        });
    }

    static IndicatorBootstrap restore(byte[] bytes, long cursor) {
        IndicatorBootstrap[] result = new IndicatorBootstrap[1];
        CheckpointCodec.decode(bytes, in -> {
            long boundary = in.readLong();
            if (boundary < 0 || boundary > cursor) throw new java.io.IOException("Invalid bootstrap boundary");
            var state = new IndicatorBootstrap(boundary);
            state.beforeTime = in.readLong(); state.beforeId = in.readLong();
            state.loadedFrom = in.readBoolean() ? in.readLong() : null;
            result[0] = state;
        });
        return result[0];
    }

    Long loadedFrom() { return loadedFrom; }

    /** Consumes at most pageSize rows, regardless of host count or their time span. */
    boolean step(SampleStore store, long cutoff, int pageSize, java.util.function.Consumer<PingSample> accept) {
        // Retention moves while loading. Expired unvisited rows no longer need recovery.
        if (beforeTime < cutoff) return true;
        var page = store.readBootstrapPage(boundary, cutoff, Long.MAX_VALUE, beforeTime, beforeId, pageSize);
        page.forEach(accept);
        if (!page.isEmpty()) {
            var last = page.getLast();
            beforeTime = last.measuredAt();
            beforeId = last.id();
            // Other IDs at this millisecond may remain on the next page. Only strictly
            // newer timestamps are guaranteed complete until that timestamp is exhausted.
            loadedFrom = beforeTime + 1;
        }
        // An exact multiple requires one final empty probe. No per-window empty queries.
        return page.size() < pageSize;
    }
}
