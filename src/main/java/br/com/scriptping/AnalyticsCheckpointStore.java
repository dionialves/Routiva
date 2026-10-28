package br.com.scriptping;

import java.util.*;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable content-addressed blocks; a transaction publishes the complete manifest. */
@Repository
public class AnalyticsCheckpointStore {
    static final class Part {
        private final String kind, host, target;
        private final int ordinal;
        private volatile String hash;
        private volatile byte[] encoded;
        private final java.util.function.Supplier<byte[]> data;
        Part(String kind, String host, String target, int ordinal, String hash, byte[] payload) {
            this(kind, host, target, ordinal, () -> payload); this.hash = hash;
        }
        private Part(String kind, String host, String target, int ordinal, java.util.function.Supplier<byte[]> data) {
            this.kind=kind; this.host=host; this.target=target; this.ordinal=ordinal; this.data=data;
        }
        static Part of(String kind, String host, String target, int ordinal, java.util.function.Supplier<byte[]> data) {
            return new Part(kind,host,target,ordinal,data);
        }
        String kind() { return kind; } String host() { return host; } String target() { return target; }
        int ordinal() { return ordinal; }
        byte[] payload() { if (encoded == null) encoded = data.get(); return encoded; }
        String hash() { if (hash == null) hash = CheckpointCodec.hash(payload()); return hash; }
    }
    record Header(String id, long reference, long cursor, String compatibility, String epoch, int parts, String manifest) {}
    record Capture(Header header, List<Part> parts) {}
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("routiva.indicators");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public AnalyticsCheckpointStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; transaction = new TransactionTemplate(manager);
    }
    String epoch() {
        jdbc.update("UPDATE analytics_checkpoint_guard SET epoch=? WHERE id=1 AND epoch IS NULL", UUID.randomUUID().toString());
        return jdbc.queryForObject("SELECT epoch FROM analytics_checkpoint_guard WHERE id=1", String.class);
    }
    List<Header> candidates() {
        return jdbc.query("SELECT * FROM analytics_checkpoint ORDER BY reference_at DESC, id DESC", (r, n) ->
            new Header(r.getString("id"), r.getLong("reference_at"), r.getLong("cursor_id"),
                r.getString("compatibility"), r.getString("epoch"), r.getInt("part_count"), r.getString("manifest")));
    }
    static String manifest(List<Part> parts) {
        return CheckpointCodec.hash(CheckpointCodec.encode(out -> {
            for (Part p : parts) {
                out.writeUTF(p.kind()); out.writeUTF(p.host()); out.writeUTF(p.target());
                out.writeInt(p.ordinal()); out.writeUTF(p.hash());
            }
        }));
    }
    void restore(Header header, Consumer<Part> consumer) {
        transaction.executeWithoutResult(status -> {
            // Prevent concurrent pruning while streaming the selected generation.
            jdbc.queryForObject("SELECT id FROM analytics_checkpoint_guard WHERE id=1 FOR UPDATE", Integer.class);
            List<Part> manifest = new ArrayList<>();
            jdbc.query(connection -> {
                var statement = connection.prepareStatement("SELECT p.*, b.payload FROM analytics_checkpoint_part p JOIN analytics_checkpoint_block b ON b.hash=p.block_hash WHERE p.checkpoint_id=? ORDER BY p.sequence_no");
                statement.setString(1, header.id()); statement.setFetchSize(128); return statement;
            }, (org.springframework.jdbc.core.RowCallbackHandler) r -> {
                byte[] bytes = r.getBytes("payload"); String hash = r.getString("block_hash");
                if (!CheckpointCodec.hash(bytes).equals(hash)) throw new IllegalArgumentException("Checkpoint checksum mismatch");
                var p = new Part(r.getString("kind"), r.getString("host_id"), r.getString("target"), r.getInt("ordinal_no"), hash, bytes);
                consumer.accept(p);
                manifest.add(new Part(p.kind(), p.host(), p.target(), p.ordinal(), hash, null));
            });
            if (manifest.size() != header.parts() || !manifest(manifest).equals(header.manifest()))
                throw new IllegalArgumentException("Checkpoint manifest mismatch");
        });
    }
    void discardInvalid(String id) {
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM analytics_checkpoint_guard WHERE id=1 FOR UPDATE", Integer.class);
            jdbc.update("DELETE FROM analytics_checkpoint WHERE id=?", id);
        });
    }
    void save(Capture capture) {
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM analytics_checkpoint_guard WHERE id=1 FOR UPDATE", Integer.class);
            Header h = capture.header();
            if (!epoch().equals(h.epoch())) throw new IllegalStateException("Source epoch changed");
            Set<String> existing = new HashSet<>(jdbc.queryForList("SELECT hash FROM analytics_checkpoint_block", String.class));
            List<Object[]> additions = new ArrayList<>();
            long progress = System.nanoTime(); int encodedParts = 0;
            for (Part p : capture.parts()) {
                if (existing.add(p.hash())) additions.add(new Object[]{p.hash(), p.payload()});
                encodedParts++;
                if (System.nanoTime()-progress >= 10_000_000_000L) {
                    LOG.info("event=CHECKPOINT_WRITE_PROGRESS checkpoint={} stage=encoding parts={} total_parts={}", h.id(), encodedParts, capture.parts().size());
                    progress = System.nanoTime();
                }
            }
            for (int i=0; i<additions.size(); i+=256) {
                jdbc.batchUpdate("INSERT INTO analytics_checkpoint_block(hash,payload) VALUES (?,?)", additions.subList(i, Math.min(i+256, additions.size())));
                if (System.nanoTime()-progress >= 10_000_000_000L) {
                    LOG.info("event=CHECKPOINT_WRITE_PROGRESS checkpoint={} stage=blocks written={} total={}", h.id(), Math.min(i+256, additions.size()), additions.size());
                    progress = System.nanoTime();
                }
            }
            jdbc.update("INSERT INTO analytics_checkpoint(id,reference_at,cursor_id,compatibility,epoch,part_count,manifest) VALUES (?,?,?,?,?,?,?)",
                h.id(), h.reference(), h.cursor(), h.compatibility(), h.epoch(), capture.parts().size(), manifest(capture.parts()));
            List<Object[]> refs = new ArrayList<>(); int sequence=0;
            for (Part p : capture.parts()) {
                refs.add(new Object[]{h.id(), sequence++, p.kind(), p.host(), p.target(), p.ordinal(), p.hash()});
                if (refs.size()==256) { writeRefs(refs); refs.clear(); }
                if (System.nanoTime()-progress >= 10_000_000_000L) {
                    LOG.info("event=CHECKPOINT_WRITE_PROGRESS checkpoint={} stage=manifest written={} total={}", h.id(), sequence, capture.parts().size());
                    progress = System.nanoTime();
                }
            }
            writeRefs(refs);
            var generations = candidates();
            for (int i=2; i<generations.size(); i++) jdbc.update("DELETE FROM analytics_checkpoint WHERE id=?", generations.get(i).id());
            jdbc.update("DELETE FROM analytics_checkpoint_block WHERE NOT EXISTS (SELECT 1 FROM analytics_checkpoint_part p WHERE p.block_hash=analytics_checkpoint_block.hash)");
        });
    }
    private void writeRefs(List<Object[]> refs) {
        if (!refs.isEmpty()) jdbc.batchUpdate("INSERT INTO analytics_checkpoint_part(checkpoint_id,sequence_no,kind,host_id,target,ordinal_no,block_hash) VALUES (?,?,?,?,?,?,?)", refs);
    }
}
