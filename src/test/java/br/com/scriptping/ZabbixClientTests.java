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
            assertThat(api.methods).containsExactly("apiinfo.version", "user.login", "host.get", "trigger.get", "host.get", "trigger.get", "host.get", "user.login", "host.get", "trigger.get", "user.logout");
            assertThat(api.violations).isEmpty();
        }
    }

    @Test void rejectsInvalidCredentialsWithoutFetchingInventory() throws Exception {
        try(var api = new FakeApi(true)) {
            var client = new ZabbixClient(api.url(), "monitor", "secret # $ value");
            assertThatThrownBy(client::fetch).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Falha no login").hasMessageNotContaining("secret");
            assertThat(api.methods).containsExactly("apiinfo.version", "user.login");
        }
    }

    @Test void supportsZabbix60AuthenticationGroupsAndSessionRenewal() throws Exception {
        try(var api = new FakeApi(false, "6.0.32")) {
            var client = new ZabbixClient(api.url(), "monitor", "secret # $ value");
            var hosts=client.fetch();
            assertThat(hosts.getFirst().groups()).extracting(Host.Group::name).containsExactly("Rede", "Matriz");
            api.expire=true;
            client.fetch();
            assertThat(api.logins.get()).isEqualTo(2);
            client.logout();
            assertThat(api.violations).isEmpty();
        }
    }

    @Test void importsAcknowledgedIcmpHostsAndClearsFlagOnNextSync() throws Exception {
        try(var api=new FakeApi(false)) {
            var client=new ZabbixClient(api.url(),"monitor","secret # $ value");
            for(String name:List.of("High ICMP ping packet loss","ICMP ping response time is too high","Unavailable by ICMP ping","Latência alta","Perda de pacotes")) {
                api.triggers="[{\"hosts\":[{\"hostid\":\"1\"}],\"lastEvent\":{\"acknowledged\":\"1\",\"name\":\""+name+"\"}}]";
                assertThat(client.fetch().getFirst().acknowledgedIcmpProblem()).as(name).isTrue();
            }
            api.triggers="[{\"hosts\":[{\"hostid\":\"1\"}],\"lastEvent\":{\"acknowledged\":\"0\",\"name\":\"Unavailable by ICMP ping\"}}]";
            assertThat(client.fetch().getFirst().acknowledgedIcmpProblem()).isFalse();
            api.triggers="[{\"hosts\":[{\"hostid\":\"1\"}],\"lastEvent\":{\"acknowledged\":\"1\",\"name\":\"Disk space low\"}}]";
            assertThat(client.fetch().getFirst().acknowledgedIcmpProblem()).isFalse();
            api.triggers="[]";
            assertThat(client.fetch().getFirst().acknowledgedIcmpProblem()).isFalse();
        }
    }

    private static class FakeApi implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger logins = new AtomicInteger();
        final List<String> methods = new ArrayList<>();
        final List<String> violations = new ArrayList<>();
        volatile boolean expire;
        String triggers="[]";
        final String version;

        FakeApi(boolean rejectLogin) throws Exception { this(rejectLogin, "7.0.0"); }

        FakeApi(boolean rejectLogin, String version) throws Exception {
            this.version=version;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api_jsonrpc.php", exchange -> {
                if(exchange.getRequestHeaders().getFirst("Content-Length")==null ||
                   exchange.getRequestHeaders().getFirst("Transfer-Encoding")!=null) violations.add("JSON body must have explicit length");
                var request = new ObjectMapper().readTree(exchange.getRequestBody());
                String method = request.path("method").asText();
                methods.add(method);
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                String response;
                if(method.equals("apiinfo.version")) {
                    if(authorization!=null || request.has("auth")) violations.add("Version must not contain authorization");
                    response="{\"result\":\""+version+"\"}";
                } else if(method.equals("user.login")) {
                    if(authorization != null) violations.add("Login must not contain authorization");
                    if(!request.path("params").path("username").asText().equals("monitor") ||
                       !request.path("params").path("password").asText().equals("secret # $ value")) violations.add("Credentials mismatch");
                    int number = logins.incrementAndGet();
                    response = rejectLogin ? "{\"error\":{\"data\":\"Login failed\"}}" : "{\"result\":\"session-"+number+"\"}";
                } else {
                    boolean legacy=version.equals("6.0.32");
                    if(legacy) {
                        if(authorization!=null || !request.path("auth").asText().equals("session-"+logins.get())) violations.add("Invalid legacy session");
                        if(method.equals("host.get") && (!request.path("params").has("selectGroups") || request.path("params").has("selectHostGroups"))) violations.add("Invalid legacy groups selector");
                    } else if(!("Bearer session-"+logins.get()).equals(authorization) || request.has("auth")) violations.add("Invalid session");
                    if(method.equals("user.logout")) response = "{\"result\":true}";
                    else if(expire) {
                        expire = false;
                        response = "{\"error\":{\"code\":-32602,\"data\":\"Session terminated, re-login, please.\"}}";
                    } else if(method.equals("trigger.get")) response="{\"result\":"+triggers+"}";
                    else response = legacy ? INVENTORY.replace("hostgroups", "groups") : INVENTORY;
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
