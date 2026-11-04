package br.com.scriptping.aggregation;

import br.com.scriptping.aggregation.MinuteAggregationService.Summary;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "ping_minute")
class PingMinute implements Persistable<MinuteKey> {

    @EmbeddedId
    private MinuteKey key;
    @Column(name = "total_pings")
    private long total;
    private long healthy;
    private long degraded;
    private long lost;
    private long errors;
    @Column(name = "latency_sum")
    private double latencySum;
    @Column(name = "last_sample_at")
    private long lastSampleAt;

    @Transient
    private boolean newRecord = true;

    protected PingMinute() {}

    @Override
    public MinuteKey getId() {
        return key;
    }

    @Override
    public boolean isNew() {
        return newRecord;
    }

    @PostLoad
    @PostPersist
    private void markPersisted() {
        newRecord = false;
    }

    PingMinute(MinuteKey key, Summary summary) {
        this.key = key;
        setSummary(summary);
    }

    void setSummary(Summary summary) {
        total = summary.total();
        healthy = summary.healthy();
        degraded = summary.degraded();
        lost = summary.lost();
        errors = summary.errors();
        latencySum = summary.sum();
        lastSampleAt = summary.last();
    }

    Summary snapshot() {
        return new Summary(key.endpoint().host(), key.endpoint().target(), key.minute(), total, healthy, degraded,
            lost, errors, latencySum, lastSampleAt);
    }
}
