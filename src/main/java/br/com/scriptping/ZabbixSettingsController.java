package br.com.scriptping;

import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Controller
@RequestMapping("/configuracao/zabbix")
public class ZabbixSettingsController {
    private final ZabbixSettings settings;
    private final ZabbixClient client;
    private final MonitorService monitor;
    public ZabbixSettingsController(ZabbixSettings settings, ZabbixClient client, MonitorService monitor) {
        this.settings = settings; this.client = client; this.monitor = monitor;
    }

    @GetMapping({"", "/hosts"})
    public String page(jakarta.servlet.http.HttpServletRequest request, Model model, HttpSession session) {
        if (session.getAttribute("zabbixCsrf") == null) session.setAttribute("zabbixCsrf", UUID.randomUUID().toString());
        var s = settings.current();
        model.addAttribute("csrf", session.getAttribute("zabbixCsrf"));
        model.addAttribute("url", s.url());
        model.addAttribute("username", s.username());
        model.addAttribute("hasPassword", !s.password().isBlank());
        model.addAttribute("syncStatus", monitor.status());
        model.addAttribute("lastSync", monitor.lastSync());
        return "zabbix-settings";
    }

    @PostMapping("/{action}")
    public String action(@PathVariable String action, @RequestParam String csrf,
        @RequestParam(defaultValue = "") String url, @RequestParam(defaultValue = "") String username,
        @RequestParam(defaultValue = "") String password,
        @RequestParam(required = false) Set<String> ids, HttpSession session, RedirectAttributes flash) {
        if (!csrf.equals(session.getAttribute("zabbixCsrf"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String destination = "/configuracao/zabbix";
        try {
            switch (action) {
                case "conexao" -> monitor.connection(url, username, password);
                case "teste" -> client.testConnection();
                case "sincronizar" -> monitor.sync();
                case "grupos" -> { monitor.selectGroups(ids == null ? Set.of() : ids); destination += "/hosts"; }
                case "hosts" -> monitor.selectHosts(ids == null ? Set.of() : ids);
                default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            flash.addFlashAttribute("message", switch (action) {
                case "teste" -> "Conexão e autenticação realizadas com sucesso.";
                case "sincronizar" -> monitor.status();
                case "conexao" -> "Conexão salva. Ao trocar a URL, selecione novamente os grupos e hosts.";
                default -> "Seleção salva. Sincronize para importar os hosts autorizados.";
            });
        } catch (ResponseStatusException ex) { throw ex;
        } catch (Exception ex) {
            flash.addFlashAttribute("error", failure(ex));
            if (action.equals("hosts")) destination += "/hosts";
        }
        return "redirect:" + destination;
    }

    @GetMapping("/inventario")
    @ResponseBody
    public Map<String, Object> inventory() {
        var groups = client.groups();
        var hosts = client.discover(groups.stream().map(Host.Group::id).collect(java.util.stream.Collectors.toSet()));
        var state = settings.current();
        return Map.of("groups", groups, "hosts", hosts, "selectedGroups", state.groups(), "selectedHosts", state.hosts());
    }

    @PostMapping("/api/{action}")
    @ResponseBody
    public org.springframework.http.ResponseEntity<Map<String, Object>> api(@PathVariable String action,
        @RequestParam String csrf, @RequestParam(defaultValue = "") String url,
        @RequestParam(defaultValue = "") String username, @RequestParam(defaultValue = "") String password,
        @RequestParam(required = false) Set<String> groups, @RequestParam(required = false) Set<String> hosts,
        HttpSession session) {
        if (!csrf.equals(session.getAttribute("zabbixCsrf"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        try {
            String message;
            switch (action) {
                case "conexao" -> { monitor.connection(url, username, password); message = "Conexão salva."; }
                case "teste" -> { client.testConnection(); message = "Conexão e autenticação realizadas com sucesso."; }
                case "sincronizar" -> { client.testConnection(); monitor.sync(); message = monitor.status(); }
                case "selecao" -> { monitor.selectInventory(groups == null ? Set.of() : groups, hosts == null ? Set.of() : hosts); message = "Seleção salva. Sincronize para importar os hosts autorizados."; }
                default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            return org.springframework.http.ResponseEntity.ok(Map.of("message", message));
        } catch (ResponseStatusException ex) { throw ex;
        } catch (Exception ex) {
            return org.springframework.http.ResponseEntity.badRequest().body(Map.of("message", failure(ex)));
        }
    }

    private String failure(Exception ex) {
        return ex instanceof IllegalArgumentException || ex instanceof IllegalStateException
            ? ex.getMessage() : "Não foi possível acessar o Zabbix. Verifique endereço, credenciais e disponibilidade da API.";
    }
}
