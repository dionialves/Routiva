package br.com.scriptping.zabbix;

import br.com.scriptping.hosts.ManagedHost;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ManagedHostTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HOST = """
        {"hostid":"42","host":"router","name":"Roteador","status":"1","flags":"0",
         "hostgroups":[{"groupid":"5","name":"Rede"}],"parentTemplates":[{"templateid":"9","name":"SNMP"}],
         "interfaces":[{"interfaceid":"10","type":"1","main":"1","useip":"1","ip":"192.0.2.1","dns":"","port":"10050","available":"1","details":[]},
           {"interfaceid":"11","type":"2","main":"1","useip":"1","ip":"192.0.2.1","dns":"","port":"161","available":"0",
            "details":{"version":"2","community":"{$SNMP_COMMUNITY}","bulk":"0","max_repetitions":"15"}},
           {"interfaceid":"12","type":"2","main":"0","useip":"1","ip":"192.0.2.2","dns":"","port":"161",
            "details":{"version":"2","community":"secondary","bulk":"1"}}]}
        """;

    private ManagedHost.Input input(String revision) {
        return new ManagedHost.Input("router-new", "Novo roteador", List.of("5"), List.of(),
            "192.0.2.3", "", true, "1161", 1, "{$MY_COMMUNITY}", true, revision);
    }
    @Test void deletionChecksRevisionAndRequiresZabbixConfirmation() throws Exception {
        try (var api = new Api("7.0.0")) {
            var host = api.client.managedHosts().getFirst();
            assertThatThrownBy(() -> api.client.deleteManagedHost("42", "stale")).hasMessageContaining("409");
            assertThat(api.calls).noneMatch(call -> call.path("method").asText().equals("host.delete"));
            api.client.deleteManagedHost("42", host.revision());
            assertThat(api.calls.getLast().path("params").get(0).asText()).isEqualTo("42");
            api.unconfirmed = true;
            assertThatThrownBy(() -> api.client.deleteManagedHost("42", host.revision())).hasMessageContaining("não confirmou");
            assertThat(api.calls.stream().filter(call -> call.path("method").asText().equals("host.delete"))).hasSize(2);
        }
    }
    @Test void discoveredHostsCannotBeDeleted() throws Exception {
        try (var api = new Api("7.0.0")) {
            api.host = HOST.replace("\"flags\":\"0\"", "\"flags\":\"4\"");
            var host = api.client.managedHosts().getFirst();
            assertThatThrownBy(() -> api.client.deleteManagedHost("42", host.revision())).hasMessageContaining("descobertos");
            assertThat(api.calls).noneMatch(call -> call.path("method").asText().equals("host.delete"));
        }
    }
    @Test void countsMonitoredEntitiesPerHostAndReadsSnmpAvailability() throws Exception {
        try (var api = new Api("7.0.0")) {
            api.host = HOST.replace("\"status\":\"1\"", "\"status\":\"0\"")
                .replace("\"available\":\"0\"", "\"available\":\"1\"");
            var host = api.client.managedHosts().getFirst();
            assertThat(host.monitoredItems()).isEqualTo(2);
            assertThat(host.monitoredTriggers()).isEqualTo(1);
            assertThat(host.snmpAvailability()).isEqualTo("available");
            for (var call : api.calls) {
                if (Set.of("item.get", "trigger.get").contains(call.path("method").asText()))
                    assertThat(call.path("params").path("monitored").asBoolean()).isTrue();
            }
            api.host = api.host.replace("\"available\":\"1\"", "\"available\":\"2\"");
            assertThat(api.client.managedHosts().getFirst().snmpAvailability()).isEqualTo("unavailable");
            api.host = api.host.replace("\"available\":\"2\"", "\"available\":\"0\"");
            assertThat(api.client.managedHosts().getFirst().snmpAvailability()).isEqualTo("unknown");
            api.host = HOST;
            var disabled = api.client.managedHosts().getFirst();
            assertThat(disabled.snmpAvailability()).isEqualTo("disabled");
            assertThat(disabled.monitoredItems()).isZero();
            assertThat(disabled.monitoredTriggers()).isZero();
        }
    }
    @Test void createsWithAllFieldsAndMacroUnchanged() throws Exception {
        try (var api = new Api("7.0.0")) {
            assertThat(api.client.saveManagedHost(null, input(null))).isEqualTo("42");
            var call = api.calls.getLast();
            assertThat(call.path("method").asText()).isEqualTo("host.create");
            var params = call.path("params");
            assertThat(params.path("host").asText()).isEqualTo("router-new");
            assertThat(params.path("name").asText()).isEqualTo("Novo roteador");
            assertThat(params.path("status").asInt()).isZero();
            assertThat(params.path("groups").get(0).path("groupid").asText()).isEqualTo("5");
            assertThat(params.path("templates").isEmpty()).isTrue();
            var face = params.path("interfaces").get(0);
            assertThat(face.path("type").asInt()).isEqualTo(2);
            assertThat(face.path("port").asText()).isEqualTo("1161");
            assertThat(face.path("details").path("version").asInt()).isEqualTo(1);
            assertThat(face.path("details").path("community").asText()).isEqualTo("{$MY_COMMUNITY}");
        }
    }
    @Test void updatesDisabledHostAndPreservesOtherInterfacesAndSnmpTuning() throws Exception {
        try (var api = new Api("7.0.0")) {
            var host = api.client.managedHosts().getFirst();
            assertThat(host.active()).isFalse();
            assertThat(host.templates()).extracting(ManagedHost.Choice::id).containsExactly("9");
            api.client.saveManagedHost("42", input(host.revision()));
            var params = api.calls.getLast().path("params");
            assertThat(params.path("hostid").asText()).isEqualTo("42");
            assertThat(params.path("interfaces")).hasSize(3);
            var agent = params.path("interfaces").get(0);
            assertThat(agent.path("interfaceid").asText()).isEqualTo("10");
            assertThat(agent.has("available")).isFalse();
            assertThat(agent.has("details")).isFalse();
            var secondary = params.path("interfaces").get(1);
            assertThat(secondary.path("ip").asText()).isEqualTo("192.0.2.2");
            var snmp = params.path("interfaces").get(2);
            assertThat(snmp.path("interfaceid").asText()).isEqualTo("11");
            assertThat(snmp.path("details").path("bulk").asText()).isEqualTo("0");
            assertThat(snmp.path("details").path("max_repetitions").asText()).isEqualTo("15");
        }
    }
    @Test void availabilityPollingDoesNotInvalidateConfigurationRevision() throws Exception {
        try (var api = new Api("7.0.0")) {
            var host = api.client.managedHosts().getFirst();
            api.host = HOST.replace("\"available\":\"1\"", "\"available\":\"0\"");
            assertThat(api.client.saveManagedHost("42", input(host.revision()))).isEqualTo("42");
        }
    }
    @Test void rejectsStaleEditsAndSnmp3WithoutMutations() throws Exception {
        try (var api = new Api("7.0.0")) {
            assertThatThrownBy(() -> api.client.saveManagedHost("42", input("stale")))
                .hasMessageContaining("409");
            api.host = HOST.replace("\"version\":\"2\"", "\"version\":\"3\"");
            var host = api.client.managedHosts().getFirst();
            assertThat(host.editable()).isFalse();
            assertThatThrownBy(() -> api.client.saveManagedHost("42", input(host.revision())))
                .hasMessageContaining("SNMPv3");
            assertThat(api.calls).noneMatch(call -> call.path("method").asText().equals("host.update"));
        }
    }
    @Test void supportsLegacyGroupsAndAuthentication() throws Exception {
        try (var api = new Api("6.0.32")) {
            api.host = HOST.replace("hostgroups", "groups");
            assertThat(api.client.managedHosts().getFirst().groups()).extracting(ManagedHost.Choice::id).containsExactly("5");
            var read = api.calls.stream().filter(call -> call.path("method").asText().equals("host.get")).findFirst().orElseThrow();
            assertThat(read.path("auth").asText()).isEqualTo("session");
            assertThat(read.path("params").has("selectGroups")).isTrue();
            api.client.saveManagedHost(null, input(null));
            assertThat(api.calls.getLast().path("auth").asText()).isEqualTo("session");
        }
    }
    @Test void rejectedAndAmbiguousWritesAreNotRetried() throws Exception {
        try (var api = new Api("7.0.0")) {
            api.reject = true;
            assertThatThrownBy(() -> api.client.saveManagedHost(null, input(null)))
                .hasMessageContaining("Zabbix recusou").hasMessageNotContaining("private-community");
            assertThat(api.calls.stream().filter(c -> c.path("method").asText().equals("host.create"))).hasSize(1);
            api.reject = false; api.unconfirmed = true;
            assertThatThrownBy(() -> api.client.saveManagedHost(null, input(null))).hasMessageContaining("não confirmou");
            assertThat(api.calls.stream().filter(c -> c.path("method").asText().equals("host.create"))).hasSize(2);
        }
    }
    @Test void validatesBeforeCallingZabbix() throws Exception {
        try (var api = new Api("7.0.0")) {
            var value = new ManagedHost.Input("router", "", List.of(), List.of(), "192.0.2.1", "", true, "161", 2, "public", true, null);
            assertThatThrownBy(() -> api.client.saveManagedHost(null, value)).hasMessageContaining("grupo");
            assertThat(api.calls).isEmpty();
        }
    }
    private static class Api implements AutoCloseable {
        final HttpServer server;
        final ZabbixClient client;
        final List<JsonNode> calls = new ArrayList<>();
        String host = HOST;
        boolean reject, unconfirmed;
        Api(String version) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                var call = JSON.readTree(exchange.getRequestBody()); calls.add(call);
                String result = switch (call.path("method").asText()) {
                    case "apiinfo.version" -> "\"" + version + "\"";
                    case "user.login" -> "\"session\"";
                    case "host.get" -> "[" + host + "]";
                    case "item.get" -> "[{\"itemid\":\"1\",\"hostid\":\"42\"},{\"itemid\":\"2\",\"hostid\":\"42\"}]";
                    case "trigger.get" -> "[{\"triggerid\":\"3\",\"hosts\":[{\"hostid\":\"42\"},{\"hostid\":\"42\"},{\"hostid\":\"99\"}]}]";
                    case "host.create", "host.update", "host.delete" -> unconfirmed ? "{}" : "{\"hostids\":[\"42\"]}";
                    default -> "[]";
                };
                String response = reject && call.path("method").asText().equals("host.create")
                    ? "{\"error\":{\"data\":\"private-community\"}}" : "{\"result\":" + result + "}";
                var bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start(); client = new ZabbixClient("http://127.0.0.1:" + server.getAddress().getPort(), "user", "password");
        }
        public void close() { server.stop(0); }
    }
}
