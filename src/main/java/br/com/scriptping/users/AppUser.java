package br.com.scriptping.users;

import jakarta.persistence.*;

@Entity
@Table(name = "app_user")
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(nullable = false, length = 120) String name;
    @Column(nullable = false, unique = true, length = 64) String username;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) Role role;
    String passwordHash;
    boolean passwordResetRequired = true;
    String activationHash;
    Long activationExpiresAt;
    long sessionVersion;
    long createdAt;
    Long lastLoginAt;
    @Version long version;

    public enum Role { ADMIN, USER }
    protected AppUser() {}
    AppUser(String name, String username, Role role) {
        this.name = name;
        this.username = username;
        this.role = role;
        this.createdAt = System.currentTimeMillis();
    }
    public Long id() { return id; }
    public String name() { return name; }
    public String username() { return username; }
    public Role role() { return role; }
    public String passwordHash() { return passwordHash; }
    public boolean needsPassword() { return passwordHash == null || passwordResetRequired; }
    public long sessionVersion() { return sessionVersion; }
}
