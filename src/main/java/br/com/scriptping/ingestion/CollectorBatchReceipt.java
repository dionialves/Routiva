package br.com.scriptping.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "collector_batch")
public class CollectorBatchReceipt {

    @EmbeddedId
    private CollectorBatchKey id;

    @Column(name = "committed_at", nullable = false)
    private long committedAt;

    private String rejection;

    protected CollectorBatchReceipt() {}

    CollectorBatchReceipt(CollectorBatchKey id, long committedAt) {
        this.id = id;
        this.committedAt = committedAt;
    }
    CollectorBatchReceipt(CollectorBatchKey id, long committedAt, String rejection) {
        this(id, committedAt);
        this.rejection = rejection;
    }
}
