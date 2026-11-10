package br.com.scriptping.zabbix;

import java.util.List;

public record Host(
    String id,
    String name,
    String ip,
    List<Group> groups,
    boolean acknowledgedIcmpProblem,
    String hostname
) {
    public Host { hostname = hostname == null ? name : hostname; }

    public Host(String id, String name, String ip, List<Group> groups, boolean acknowledgedIcmpProblem) {
        this(id, name, ip, groups, acknowledgedIcmpProblem, name);
    }

    public Host(String id, String name, String ip, List<Group> groups) {
        this(id, name, ip, groups, false);
    }

    public record Group(String id, String name) {}
}
