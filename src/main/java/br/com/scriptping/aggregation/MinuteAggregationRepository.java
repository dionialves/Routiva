package br.com.scriptping.aggregation;

import java.util.List;

import br.com.scriptping.ingestion.PingSample;
import br.com.scriptping.aggregation.MinuteAggregationService.Summary;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MinuteAggregationRepository extends JpaRepository<PingMinute, MinuteKey> {

    @Query(value = "select id from ping_ingestion_guard where id = 1 for update", nativeQuery = true)
    int lockIngestion();

    @Query(value = "SELECT raw_cursor_id FROM ping_aggregation_progress WHERE id=1", nativeQuery = true)
    long processedRawCursor();

    @Query("select coalesce(max(s.id), 0L) from PingSampleRow s")
    long latestSampleId();

    @Query("select p from AggregationProgress p where p.id = 1")
    AggregationProgress progress();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AggregationProgress p where p.id = 1")
    AggregationProgress lockProgress();

    @Query("""
        select new br.com.scriptping.ingestion.PingSample(
            s.id, s.hostId, s.target, s.measuredAt, s.latency, s.error, s.latencyLimit)
        from PingSampleRow s where s.id > :after and s.id <= :through order by s.id
        """)
    List<PingSample> samples(@Param("after") long after, @Param("through") long through, Pageable page);

    @Query("select m from PingMinute m where m.key in :keys")
    List<PingMinute> minutes(@Param("keys") List<MinuteKey> keys);

    String SUMMARY = "select new br.com.scriptping.aggregation.MinuteAggregationService$Summary(";
    String COUNTS = """
        sum(m.total), sum(m.healthy), sum(m.degraded), sum(m.lost), sum(m.errors),
        sum(m.latencySum), max(m.lastSampleAt))
        from PingMinute m
        where m.key.minute >= :from and m.key.minute < :to and m.key.endpoint in :hosts
        """;

    @Query(SUMMARY + "m.key.endpoint.host, m.key.endpoint.target, 0L, " + COUNTS
        + "group by m.key.endpoint.host, m.key.endpoint.target")
    List<Summary> totals(@Param("from") long from, @Param("to") long to,
        @Param("hosts") List<HostTarget> hosts);

    @Query(SUMMARY + "'', '', m.key.minute, " + COUNTS + "group by m.key.minute")
    List<Summary> series(@Param("from") long from, @Param("to") long to,
        @Param("hosts") List<HostTarget> hosts);
}
