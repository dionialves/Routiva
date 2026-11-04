package br.com.scriptping.aggregation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "ping_sample")
class PingSampleRow {

    @Id
    private long id;
    @Column(name = "host_id")
    private String hostId;
    private String target;
    @Column(name = "measured_at")
    private long measuredAt;
    private Double latency;
    private String error;
    @Column(name = "latency_limit")
    private double latencyLimit;

    protected PingSampleRow() {}
}
