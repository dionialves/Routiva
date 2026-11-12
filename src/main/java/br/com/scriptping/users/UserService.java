package br.com.scriptping.users;

import java.security.SecureRandom;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final SecureRandom random = new SecureRandom();
    public UserService(UserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }
    public record View(Long id, String name, String username, AppUser.Role role,
                       boolean passwordResetRequired, long createdAt, Long lastLoginAt, long version) {}
    public record Input(String name, String username, AppUser.Role role, Long version) {}
    public record Saved(View user, String activationCode) {}
    public static String normalize(String value) { return value == null ? "" : value.strip().toLowerCase(Locale.ROOT); }
    public View view(AppUser u) {
        return new View(u.id, u.name, u.username, u.role, u.needsPassword(), u.createdAt, u.lastLoginAt, u.version);
    }
    @Transactional(readOnly = true)
    public List<View> list() { return users.findAllByOrderByNameAsc().stream().map(this::view).toList(); }
    @Transactional
    public Saved save(Long id, Input input) {
        if (input.name() == null || input.name().isBlank() || input.name().strip().length() > 120
            || !normalize(input.username()).matches("[a-z0-9][a-z0-9.@+_-]{2,63}") || input.role() == null)
            throw bad("Informe nome, perfil e usuário de 3 a 64 caracteres. Use letras, números, ponto, @, +, hífen ou sublinhado.");
        var all = users.lockUsers();
        var u = id == null ? new AppUser(input.name().strip(), normalize(input.username()), input.role())
            : all.stream().filter(item -> item.id.equals(id)).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (id != null && (input.version() == null || input.version() != u.version))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cadastro alterado. Reabra o usuário antes de salvar.");
        if (all.stream().anyMatch(item -> item.username.equals(normalize(input.username())) && !Objects.equals(item.id, id)))
            throw bad("Este usuário já está cadastrado.");
        if (u.role == AppUser.Role.ADMIN && input.role() != AppUser.Role.ADMIN
            && all.stream().filter(item -> item.role == AppUser.Role.ADMIN).count() <= 1)
            throw bad("O sistema precisa manter pelo menos um Administrador.");
        if (id != null && (!u.username.equals(normalize(input.username())) || u.role != input.role())) u.sessionVersion++;
        u.name = input.name().strip(); u.username = normalize(input.username()); u.role = input.role();
        String code = id == null ? issueCode(u) : null;
        users.saveAndFlush(u);
        return new Saved(view(u), code);
    }
    @Transactional
    public Saved reset(Long id) {
        var u = users.lockById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        u.passwordResetRequired = true;
        u.sessionVersion++;
        String code = issueCode(u);
        users.flush();
        return new Saved(view(u), code);
    }
    @Transactional
    public String bootstrap() {
        var u = users.findByUsername("admin").orElse(null);
        // Never recreate a default account after the installation already has users.
        if (u == null && users.count() != 0) return null;
        if (u == null) u = new AppUser("Administrador", "admin", AppUser.Role.ADMIN);
        if (u.passwordHash != null) return null;
        String code = issueCode(u);
        users.saveAndFlush(u);
        return code;
    }
    private String issueCode(AppUser u) {
        byte[] bytes = new byte[18]; random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        u.activationHash = passwords.encode(code);
        u.activationExpiresAt = System.currentTimeMillis() + 24 * 60 * 60 * 1000L;
        return code;
    }
    @Transactional
    public AppUser authenticate(Long id, long sessionVersion, String password) {
        var u = users.lockById(id).orElseThrow(() -> bad("Acesso inválido. Informe o usuário novamente."));
        if (u.sessionVersion != sessionVersion || u.needsPassword() || password == null
            || password.length() > 128 || !passwords.matches(password, u.passwordHash))
            throw bad("Senha inválida ou acesso expirado.");
        u.lastLoginAt = System.currentTimeMillis();
        return u;
    }
    @Transactional
    public AppUser setPassword(Long id, long sessionVersion, String code, String password, String confirmation) {
        var u = users.lockById(id).orElseThrow(() -> bad("Acesso inválido. Informe o usuário novamente."));
        if (u.sessionVersion != sessionVersion || !u.needsPassword() || u.activationHash == null
            || u.activationExpiresAt == null || u.activationExpiresAt < System.currentTimeMillis()
            || code == null || code.length() > 128 || !passwords.matches(code, u.activationHash))
            throw bad("Código inválido ou expirado. Solicite um novo código ao Administrador.");
        if (!Objects.equals(password, confirmation)) throw bad("A confirmação não corresponde à senha.");
        if (password == null || password.length() < 12 || password.length() > 128
            || !password.codePoints().anyMatch(Character::isUpperCase)
            || !password.codePoints().anyMatch(Character::isLowerCase)
            || !password.codePoints().anyMatch(Character::isDigit)
            || !password.codePoints().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c)))
            throw bad("Use de 12 a 128 caracteres, com maiúscula, minúscula, número e caractere especial.");
        u.passwordHash = passwords.encode(password);
        u.passwordResetRequired = false; u.activationHash = null; u.activationExpiresAt = null;
        u.sessionVersion++; u.lastLoginAt = System.currentTimeMillis();
        return u;
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
