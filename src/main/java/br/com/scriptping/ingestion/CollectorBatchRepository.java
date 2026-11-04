package br.com.scriptping.ingestion;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface CollectorBatchRepository extends Repository<CollectorBatchReceipt, CollectorBatchKey> {

    boolean existsById(CollectorBatchKey id);

    CollectorBatchReceipt save(CollectorBatchReceipt receipt);

    @Modifying
    @Query("DELETE FROM CollectorBatchReceipt r WHERE r.committedAt < :cutoff")
    void deleteExpired(@Param("cutoff") long cutoff);
}
