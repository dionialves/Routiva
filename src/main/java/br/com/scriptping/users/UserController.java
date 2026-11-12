package br.com.scriptping.users;

import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class UserController {
    private final UserService users;
    public UserController(UserService users) { this.users = users; }
    @GetMapping("/configuracao/usuarios") public String page() { return "users"; }
    @GetMapping("/api/users") @ResponseBody public List<UserService.View> list() { return users.list(); }
    @PostMapping("/api/users") @ResponseBody public UserService.Saved create(@RequestBody UserService.Input input) { return users.save(null, input); }
    @PutMapping("/api/users/{id}") @ResponseBody public UserService.Saved update(@PathVariable Long id, @RequestBody UserService.Input input) { return users.save(id, input); }
    @PostMapping("/api/users/{id}/reset-password") @ResponseBody public UserService.Saved reset(@PathVariable Long id) { return users.reset(id); }
    @ExceptionHandler(ResponseStatusException.class) @ResponseBody
    public ResponseEntity<?> invalid(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", Objects.requireNonNullElse(ex.getReason(), "Não foi possível salvar o usuário.")));
    }
    @ExceptionHandler({DataIntegrityViolationException.class, org.springframework.orm.ObjectOptimisticLockingFailureException.class}) @ResponseBody
    public ResponseEntity<?> conflict(Exception ex) {
        return ResponseEntity.status(409).body(Map.of("message", "Cadastro duplicado ou alterado. Atualize a lista e tente novamente."));
    }
}
