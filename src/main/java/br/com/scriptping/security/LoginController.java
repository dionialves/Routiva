package br.com.scriptping.security;

import br.com.scriptping.users.*;
import jakarta.servlet.http.*;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/login")
public class LoginController {
    private static final String CHALLENGE = "loginChallenge";
    private record Challenge(Long userId, long sessionVersion, String username, boolean needsPassword, long expiresAt) implements Serializable {}
    private final UserRepository users;
    private final UserService service;
    private final LoginThrottle throttle;
    private final SecurityContextRepository contexts;
    public LoginController(UserRepository users, UserService service, LoginThrottle throttle, SecurityContextRepository contexts) {
        this.users = users; this.service = service; this.throttle = throttle; this.contexts = contexts;
    }
    @GetMapping
    public String login(HttpServletRequest request) {
        if (request.getUserPrincipal() != null) return "redirect:/disponibilidade";
        if (request.getSession(false) != null) request.getSession().removeAttribute(CHALLENGE);
        return "login";
    }
    @PostMapping(value = "/usuario", produces = "application/json")
    @ResponseBody
    public ResponseEntity<?> identifyAsync(@RequestParam String username, HttpServletRequest request) {
        var model = new ExtendedModelMap();
        String result = identify(username, request, model);
        if (!result.startsWith("redirect:")) {
            return ResponseEntity.badRequest().body(Map.of("message", "Não foi possível continuar. Confira os dados ou aguarde antes de tentar novamente."));
        }
        var challenge = challenge(request);
        return ResponseEntity.ok(Map.of("username", challenge.username, "setup", challenge.needsPassword));
    }
    @PostMapping(value = {"/senha", "/nova-senha"}, produces = "application/json")
    @ResponseBody
    public ResponseEntity<?> passwordAsync(@RequestParam(defaultValue = "") String password,
        @RequestParam(defaultValue = "") String confirmation, @RequestParam(defaultValue = "") String code,
        HttpServletRequest request, HttpServletResponse response) {
        var model = new ExtendedModelMap();
        String result = password(password, confirmation, code, request, response, model);
        if (result.equals("redirect:/disponibilidade")) return ResponseEntity.ok(Map.of("redirect", "/disponibilidade"));
        if (result.equals("redirect:/login")) return ResponseEntity.status(409).body(Map.of("message", "Sua identificação expirou. Informe o usuário novamente.", "reset", true));
        boolean invalidCode = false;
        var challenge = challenge(request);
        if (challenge != null && challenge.needsPassword) {
            try { service.validateActivation(challenge.userId, challenge.sessionVersion, code); }
            catch (ResponseStatusException ex) { invalidCode = true; }
        }
        return ResponseEntity.badRequest().body(Map.of("message", model.getAttribute("error"), "codeInvalid", invalidCode));
    }
    @PostMapping(value = "/validar-codigo", produces = "application/json")
    @ResponseBody
    public ResponseEntity<?> validateCode(@RequestParam(defaultValue = "") String code, HttpServletRequest request) {
        var challenge = challenge(request);
        if (challenge == null || !challenge.needsPassword) return ResponseEntity.status(409).body(Map.of(
            "message", "Sua identificação expirou. Informe o usuário novamente.", "reset", true));
        try {
            throttle.check(request.getRemoteAddr(), challenge.username);
            service.validateActivation(challenge.userId, challenge.sessionVersion, code);
            return ResponseEntity.ok(Map.of("validated", true));
        } catch (ResponseStatusException ex) {
            return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", ex.getReason()));
        }
    }
    @PostMapping("/limpar")
    @ResponseBody
    public ResponseEntity<?> clear(HttpServletRequest request) {
        if (request.getSession(false) != null) request.getSession().removeAttribute(CHALLENGE);
        return ResponseEntity.ok(Map.of("cleared", true));
    }
    @PostMapping("/usuario")
    public String identify(@RequestParam String username, HttpServletRequest request, Model model) {
        request.getSession().removeAttribute(CHALLENGE);
        username = UserService.normalize(username);
        try {
            if (username.length() > 64) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Usuário não encontrado.");
            throttle.check(request.getRemoteAddr(), username);
            var user = users.findByUsername(username).orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Usuário não encontrado."));
            request.changeSessionId();
            request.getSession().setAttribute(CHALLENGE, new Challenge(user.id(), user.sessionVersion(), user.username(), user.needsPassword(), System.currentTimeMillis() + 10 * 60 * 1000));
            return "redirect:/login/" + (user.needsPassword() ? "nova-senha" : "senha");
        } catch (ResponseStatusException ex) {
            model.addAttribute("error", ex.getReason()); model.addAttribute("username", username); return "login";
        }
    }
    @GetMapping({"/senha", "/nova-senha"})
    public String passwordPage(HttpServletRequest request, Model model) {
        var challenge = challenge(request);
        if (challenge == null) return "redirect:/login";
        String expected = "/login/" + (challenge.needsPassword ? "nova-senha" : "senha");
        if (!request.getRequestURI().equals(expected)) return "redirect:" + expected;
        model.addAttribute("username", challenge.username);
        model.addAttribute("setup", challenge.needsPassword);
        return challenge.needsPassword ? "password-setup" : "login-password";
    }
    @PostMapping({"/senha", "/nova-senha"})
    public String password(@RequestParam(defaultValue = "") String password,
        @RequestParam(defaultValue = "") String confirmation, @RequestParam(defaultValue = "") String code,
        HttpServletRequest request, HttpServletResponse response, Model model) {
        var challenge = challenge(request);
        if (challenge == null) return "redirect:/login";
        boolean setup = request.getRequestURI().equals("/login/nova-senha");
        if (setup != challenge.needsPassword) return "redirect:/login";
        try {
            throttle.check(request.getRemoteAddr(), challenge.username);
            AppUser user = setup ? service.setPassword(challenge.userId, challenge.sessionVersion, code, password, confirmation)
                : service.authenticate(challenge.userId, challenge.sessionVersion, password);
            request.getSession().removeAttribute(CHALLENGE);
            request.changeSessionId();
            new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository().saveToken(null, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(UserPrincipal.from(user), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))));
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            return "redirect:/disponibilidade";
        } catch (ResponseStatusException ex) {
            model.addAttribute("error", ex.getReason()); model.addAttribute("username", challenge.username);
        model.addAttribute("setup", challenge.needsPassword);
            return setup ? "password-setup" : "login-password";
        }
    }
    private Challenge challenge(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(CHALLENGE) instanceof Challenge challenge)) return null;
        if (challenge.expiresAt < System.currentTimeMillis()) { session.removeAttribute(CHALLENGE); return null; }
        return challenge;
    }
}
