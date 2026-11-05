package br.com.scriptping.zabbix;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "zabbix_settings")
class ZabbixSettingsEntity {

    @Id
    private int id = 1;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    protected ZabbixSettingsEntity() {}

    ZabbixSettingsEntity(String payload) {
        this.payload = payload;
    }

    String payload() {
        return payload;
    }
}
