package br.com.scriptping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ZabbixClientTests {
    private static final String INVENTORY = """
        {"jsonrpc":"2.0","id":1,"result":[{"hostid":"1","host":"router","name":"Roteador principal","interfaces":[{"ip":"10.0.0.2","main":"0","type":"1"},{"ip":"10.0.0.1","main":"1","type":"1"}],"hostgroups":[{"groupid":"5","name":"Rede"},{"groupid":"6","name":"Matriz"}]}]}
        """;

    @Test void logsInReusesSessionRenewsAndLogsOut() throws Exception {
        try(var api = new FakeApi(false)) {
            var client = new ZabbixClient(api.url(), "monitor", "secret # $ value");
            var hosts = client.fetch();
            assertThat(hosts).hasSize(1);
            assertThat(hosts.getFirst().name()).isEqualTo("Roteador principal");
            assertThat(hosts.getFirst().ip()).isEqualTo("10.0.0.1");
            assertThat(hosts.getFirst().groups()).hasSize(2);
            client.fetch();
            assertThat(api.logins.get()).isEqualTo(1);
            api.expire = true;
            client.fetch();
            assertThat(api.logins.get()).isEqualTo(2);
            client.logout();
            assertThat(api.methods).containsExactly("user.login", "host.get", "host.get", "host.get", "user.login", "host.get", "user.logout");
            assertThat(api.violations).isEmpty();
        }
    }

    @Test void rejectsInvalidCredentialsWithoutFetchingInventory() throws Exception {
        try(var api = new FakeApi(true)) {
            var client = new ZabbixClient(api.url(), "monitor", "secret # $ value");
            assertThatThrownBy(client::fetch).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Falha no login").hasMessageNotContaining("secret");
            assertThat(api.methods).containsExactly("user.login");
        }
    }

    private static class FakeApi implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger logins = new AtomicInteger();
        final List<String> methods = new ArrayList<>();
        final List<String> violations = new ArrayList<>();
        volatile boolean expire;

        FakeApi(boolean rejectLogin) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api_jsonrpc.php", exchange -> {
                var request = new ObjectMapper().readTree(exchange.getRequestBody());
                String method = request.path("method").asText();
                methods.add(method);
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                String response;
                if(method.equals("user.login")) {
                    if(authorization != null) violations.add("Login must not contain authorization");
                    if(!request.path("params").path("username").asText().equals("monitor") ||
                       !request.path("params").path("password").asText().equals("secret # $ value")) violations.add("Credentials mismatch");
                    int number = logins.incrementAndGet();
                    response = rejectLogin ? "{\"error\":{\"data\":\"Login failed\"}}" : "{\"result\":\"session-"+number+"\"}";
                } else {
                    if(!("Bearer session-"+logins.get()).equals(authorization)) violations.add("Invalid session");
                    if(method.equals("user.logout")) response = "{\"result\":true}";
                    else if(expire) {
                        expire = false;
                        response = "{\"error\":{\"code\":-32602,\"data\":\"Session terminated, re-login, please.\"}}";
                    } else response = INVENTORY;
                }
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try(var output = exchange.getResponseBody()) {output.write(body);}
            });
            server.start();
        }
        String url() {return "http://127.0.0.1:"+server.getAddress().getPort()+"/api_jsonrpc.php";}
        public void close() {server.stop(0);}
    }
}
