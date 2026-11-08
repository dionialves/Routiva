package br.com.scriptping.ingestion;

import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface CollectorBatchRepository extends Repository<CollectorBatchReceipt, CollectorBatchKey> {

    boolean existsById(CollectorBatchKey id);

    CollectorBatchReceipt save(CollectorBatchReceipt receipt);

    @Modifying
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    @Query(value = """
        DELETE FROM collector_batch WHERE (collector_id, batch_id) IN (
            SELECT collector_id, batch_id FROM collector_batch WHERE committed_at < :cutoff
            ORDER BY committed_at, collector_id, batch_id LIMIT :batchSize
        )
        """, nativeQuery = true)
    int deleteExpired(@Param("cutoff") long cutoff, @Param("batchSize") int batchSize);
}
