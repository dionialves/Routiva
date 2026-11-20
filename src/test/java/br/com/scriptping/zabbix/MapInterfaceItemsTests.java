package br.com.scriptping.zabbix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MapInterfaceItemsTests {
    @Test void discoversInBatchesAndRequestsOnlySelectedItemValuesWithHostStatus() throws Exception {
        var json = new ObjectMapper();
        var requests = new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var request = json.readTree(exchange.getRequestBody()); requests.add(request);
            String result = switch (request.path("method").asText()) {
                case "apiinfo.version" -> "\"7.0.0\"";
                case "user.login" -> "\"test-session\"";
                case "item.get" -> "[{\"itemid\":\"7\",\"hostid\":\"1\"}]";
                default -> "[]";
            };
            byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var client = new ZabbixClient("http://127.0.0.1:" + server.getAddress().getPort(), "reader", "test");
            var hosts = new HashSet<String>(); for (int i = 1; i <= 101; i++) hosts.add("" + i);
            assertThat(client.mapInterfaceItems(hosts)).hasSize(2);
            assertThat(client.mapItemValues(Set.of("7", "8"))).hasSize(1);
            var items = requests.stream().filter(row -> row.path("method").asText().equals("item.get")).toList();
            assertThat(items).hasSize(3);
            assertThat(items.get(0).path("params").path("hostids")).hasSize(100);
            assertThat(items.get(1).path("params").path("hostids")).hasSize(1);
            assertThat(items.getFirst().path("params").path("search").path("key_").asText()).isEqualTo("net.if.");
            var values = items.getLast().path("params");
            assertThat(values.path("itemids")).hasSize(2);
            assertThat(values.path("output").toString()).contains("lastclock", "lastvalue", "state", "status");
            assertThat(values.path("selectHosts").toString()).contains("status");
            client.logout();
        } finally { server.stop(0); }
    }
}
