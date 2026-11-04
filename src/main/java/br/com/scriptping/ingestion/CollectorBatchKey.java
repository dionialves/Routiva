package br.com.scriptping.ingestion;

import java.io.Serializable;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record CollectorBatchKey(
    @Column(name = "collector_id", length = 64) String collectorId,
    @Column(name = "batch_id") long batchId
) implements Serializable {}
