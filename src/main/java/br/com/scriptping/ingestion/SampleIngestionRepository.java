package br.com.scriptping.ingestion;

import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface SampleIngestionRepository extends Repository<PingSampleRow, Long>, SampleBatchWriter {

    @Query(value = "SELECT id FROM ping_ingestion_guard WHERE id = 1 FOR UPDATE", nativeQuery = true)
    int lockIngestion();

    @Modifying
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    @Query(value = """
        DELETE FROM ping_sample WHERE id IN (
            SELECT id FROM ping_sample
            WHERE measured_at < :cutoff AND id <= :processedCursor
            ORDER BY measured_at, id LIMIT :batchSize
        )
        """, nativeQuery = true)
    int deleteExpiredSamples(@Param("cutoff") long cutoff,
        @Param("processedCursor") long processedCursor, @Param("batchSize") int batchSize);
}
