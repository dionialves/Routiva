package br.com.scriptping;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.util.*;
@Component
public class ZabbixClient {
 private final String url, username, password;
 private final RestClient client;
 private String session;

 public ZabbixClient(@Value("${zabbix.url}") String url,
                     @Value("${zabbix.username}") String username,
                     @Value("${zabbix.password}") String password) {
  this.url=url; this.username=username; this.password=password;
  var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
  factory.setReadTimeout(Duration.ofSeconds(20));
  client=RestClient.builder().requestFactory(factory).build();
 }

 public boolean configured(){return !url.isBlank() && !username.isBlank() && !password.isBlank();}

 private JsonNode request(String method, Object params, String authentication) {
  var request=client.post().uri(url);
  if(authentication!=null) request.header("Authorization", "Bearer "+authentication);
  JsonNode root=request.body(Map.of("jsonrpc","2.0","id",1,"method",method,"params",params))
      .retrieve().body(JsonNode.class);
  if(root==null) throw new IllegalStateException("Resposta vazia da API Zabbix.");
  return root;
 }

 private void login() {
  var root=request("user.login",Map.of("username",username,"password",password),null);
  if(root.has("error") || !root.path("result").isTextual() || root.path("result").asText().isBlank())
   throw new IllegalStateException("Falha no login Zabbix; verifique usuário, senha e acesso à API.");
  session=root.path("result").asText();
 }

 private boolean expired(JsonNode root) {
  String data=root.path("error").path("data").asText().toLowerCase(Locale.ROOT);
  return data.contains("session terminated") || data.contains("not authorized") || data.contains("re-login");
 }

 public synchronized List<Host> fetch() {
  if(session==null) login();
  var params=Map.of("output",List.of("hostid","host","name"),
      "selectHostGroups",List.of("groupid","name"),
      "selectInterfaces",List.of("ip","main","type"),"filter",Map.of("status","0"));
  JsonNode root=request("host.get",params,session);
  if(expired(root)) {
   session=null;
   login();
   root=request("host.get",params,session);
   if(expired(root)) session=null;
  }
  if(root.has("error") || !root.path("result").isArray())
   throw new IllegalStateException("Resposta inválida da API Zabbix; verifique versão e permissões.");
  List<Host> hosts=new ArrayList<>();
  for(JsonNode h:root.path("result")) {
   List<JsonNode> interfaces=new ArrayList<>(); h.path("interfaces").forEach(interfaces::add);
   interfaces.sort(Comparator.comparingInt(i -> (i.path("main").asText().equals("1")?0:2)+(i.path("type").asText().equals("1")?0:1)));
   String ip=interfaces.stream().map(i->i.path("ip").asText()).filter(s->!s.isBlank()).findFirst().orElse("");
   List<Host.Group> groups=new ArrayList<>(); for(JsonNode g:h.path("hostgroups")) groups.add(new Host.Group(g.path("groupid").asText(),g.path("name").asText()));
   if(groups.isEmpty()) groups.add(new Host.Group("ungrouped","Sem grupo"));
   hosts.add(new Host(h.path("hostid").asText(),h.path("name").asText(h.path("host").asText()),ip,List.copyOf(groups)));
  } return List.copyOf(hosts);
 }

 @jakarta.annotation.PreDestroy
 public synchronized void logout() {
  if(session==null) return;
  try {request("user.logout",List.of(),session);}
  catch(Exception ignored) { /* Shutdown must finish even if Zabbix is unavailable. */ }
  finally {session=null;}
 }
}
