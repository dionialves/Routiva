package br.com.scriptping.hosts;

import java.util.List;

/** Host configuration backed by the Zabbix host ID, separate from monitoring inventory. */
public record ManagedHost(String id, String hostname, String visibleName, List<Choice> groups,
                          List<Choice> templates, String interfaceId, String ip, String dns,
                          boolean useIp, String port, int snmpVersion, String community,
                          boolean active, String revision, boolean editable, int monitoredItems, int monitoredTriggers, String snmpAvailability, String description) {
    public record Choice(String id, String name) {}
    public record Input(String hostname, String visibleName, List<String> groupIds,
                        List<String> templateIds, String ip, String dns, boolean useIp,
                        String port, int snmpVersion, String community, boolean active,
                        String revision, String description) {}
}
