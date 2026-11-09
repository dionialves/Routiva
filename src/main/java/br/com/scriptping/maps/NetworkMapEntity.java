package br.com.scriptping.maps;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "network_map")
class NetworkMapEntity {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private long revision;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String layout;

    @Column(name = "updated_at_ms")
    private Long updatedAt;

    protected NetworkMapEntity() {}

    NetworkMapEntity(String id, long revision, String layout, Long updatedAt) {
        this.id = id;
        this.revision = revision;
        this.layout = layout;
        this.updatedAt = updatedAt;
    }

    String id() { return id; }
    long revision() { return revision; }
    String layout() { return layout; }
    Long updatedAt() { return updatedAt; }
}
