package br.com.scriptping.ingestion;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface SampleIngestionRepository extends Repository<PingSampleRow, Long>, SampleBatchWriter {

    @Query(value = "SELECT id FROM ping_ingestion_guard WHERE id = 1 FOR UPDATE", nativeQuery = true)
    int lockIngestion();

    @Modifying
    @Query("DELETE FROM PingSampleRow s WHERE s.measuredAt < :cutoff AND s.id <= :processedCursor")
    void deleteExpiredSamples(@Param("cutoff") long cutoff, @Param("processedCursor") long processedCursor);
}
