package br.com.scriptping.zabbix;


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
    private final ZabbixService monitor;
    public ZabbixSettingsController(ZabbixSettings settings, ZabbixClient client, ZabbixService monitor) {
        this.settings = settings; this.client = client; this.monitor = monitor;
    }

    @GetMapping({"", "/hosts"})
    public String page(jakarta.servlet.http.HttpServletRequest request, Model model, HttpSession session) {
        if (session.getAttribute("zabbixCsrf") == null) session.setAttribute("zabbixCsrf", UUID.randomUUID().toString());
        var s = settings.current();
        model.addAttribute("csrf", session.getAttribute("zabbixCsrf"));
        model.addAttribute("configured", client.configured());
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
        return monitor.inventory();
    }

    /** Last persisted operation remains available even when the remote API is offline. */
    @GetMapping("/resultado")
    @ResponseBody
    public Map<String, Object> lastResult() {
        var state = settings.current();
        return Map.of("result", state.importResult(), "imported", state.imported());
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
                case "selecao" -> { return org.springframework.http.ResponseEntity.ok(monitor.saveAndImport(hosts == null ? Set.of() : hosts)); }
                default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            return org.springframework.http.ResponseEntity.ok(Map.of("message", message));
        } catch (ResponseStatusException ex) { throw ex;
        } catch (Exception ex) {
            return org.springframework.http.ResponseEntity.badRequest().body(Map.of("message", failure(ex)));
        }
    }

    public record ImportRequest(Set<String> hosts) {}

    @PostMapping("/api/importar")
    @ResponseBody
    public org.springframework.http.ResponseEntity<Map<String, Object>> importHosts(
        @RequestHeader("X-Zabbix-CSRF") String csrf, @RequestBody ImportRequest body, HttpSession session) {
        if (!csrf.equals(session.getAttribute("zabbixCsrf"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        try {
            return org.springframework.http.ResponseEntity.ok(monitor.saveAndImport(
                body.hosts() == null ? Set.of() : body.hosts()));
        } catch (Exception ex) {
            return org.springframework.http.ResponseEntity.badRequest().body(Map.of("message", failure(ex)));
        }
    }

    private String failure(Exception ex) {
        return ex instanceof IllegalArgumentException || ex instanceof IllegalStateException
            ? ex.getMessage() : "Não foi possível acessar o Zabbix. Verifique endereço, credenciais e disponibilidade da API.";
    }
}
