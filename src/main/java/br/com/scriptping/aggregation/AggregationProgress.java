package br.com.scriptping.aggregation;

import br.com.scriptping.aggregation.MinuteAggregationService.Progress;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ping_aggregation_progress")
class AggregationProgress {

    @Id
    private int id;
    @Column(name = "cursor_id")
    private long cursor;
    @Column(name = "boundary_id")
    private long boundary;
    @Column(name = "processed_at")
    private Long processedAt;
    @Column(name = "complete_through")
    private long completeThrough;

    protected AggregationProgress() {}

    Progress snapshot() {
        return new Progress(cursor, boundary, processedAt, completeThrough);
    }

    void setProgress(Progress progress) {
        cursor = progress.cursor();
        boundary = progress.boundary();
        processedAt = progress.processedAt();
        completeThrough = progress.completeThrough();
    }
}
