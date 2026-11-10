package br.com.scriptping.cleanup;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CleanupStore {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public CleanupStore(JdbcTemplate shared) {
        jdbc = new JdbcTemplate(shared.getDataSource());
        jdbc.setQueryTimeout(5);
        named = new NamedParameterJdbcTemplate(jdbc);
    }

    long cursor() {
        return jdbc.queryForObject("SELECT raw_cursor_id FROM ping_aggregation_progress WHERE id=1", Long.class);
    }

    Long oldest() {
        return jdbc.queryForObject("SELECT MIN(measured_at) FROM ping_sample", Long.class);
    }

    long latest() {
        return jdbc.queryForObject("SELECT COALESCE(MAX(measured_at), 0) FROM ping_sample", Long.class);
    }

    List<Long> discover(long start, long end, long cursor, int limit) {
        return jdbc.queryForList("""
            SELECT id FROM ping_sample WHERE measured_at >= ? AND measured_at < ? AND id <= ?
            ORDER BY measured_at, id LIMIT ?
            """, Long.class, start, end, cursor, limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void delete(List<Long> ids) {
        named.update("DELETE FROM ping_sample WHERE id IN (:ids)", java.util.Map.of("ids", ids));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public int deleteReceipts(long cutoff, int limit) {
        return jdbc.update("""
            DELETE FROM collector_batch WHERE (collector_id, batch_id) IN (
                SELECT collector_id, batch_id FROM collector_batch WHERE committed_at < ?
                ORDER BY committed_at, collector_id, batch_id LIMIT ?)
            """, cutoff, limit);
    }
}
