package br.com.scriptping.ingestion;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface CollectorBatchRepository extends Repository<CollectorBatchReceipt, CollectorBatchKey> {

    boolean existsById(CollectorBatchKey id);

    CollectorBatchReceipt save(CollectorBatchReceipt receipt);
}
