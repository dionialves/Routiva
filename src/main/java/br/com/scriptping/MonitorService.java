package br.com.scriptping;

import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MonitorService {

    private final PacketLossAnalysisService analysis;
    private final ZabbixClient zabbix;
    private final CollectorClient collector;
    private volatile List<Host> hosts = List.of();
    private volatile String status = "Aguardando sincronização";
    private volatile Long lastSync;

    public MonitorService(
        ZabbixClient zabbix,
        CollectorClient collector,
        PacketLossAnalysisService analysis
    ) {
        this.analysis = analysis;
        this.zabbix = zabbix;
        this.collector = collector;
    }

    @Scheduled(fixedDelayString = "${zabbix.sync-ms}")
    public void sync() {
        if (!zabbix.configured()) {
            status = "Configure ZABBIX_URL, ZABBIX_USERNAME e ZABBIX_PASSWORD no .env para começar";
            return;
        }
        try {
            hosts = zabbix.fetch();
            analysis.retainHosts(hosts);
            collector.synchronize(hosts);
            lastSync = System.currentTimeMillis();
            status = "Zabbix conectado";
        } catch (Exception ex) {
            status = "Falha na sincronização com Zabbix; mantendo último inventário";
            LoggerFactory.getLogger(getClass()).warn(
                "Falha na sincronização Zabbix ({})",
                ex.getClass().getSimpleName()
            );
        }
    }

    public List<Host> hosts() {
        return hosts;
    }

    public String status() {
        return status;
    }

    public Long lastSync() {
        return lastSync;
    }
}
