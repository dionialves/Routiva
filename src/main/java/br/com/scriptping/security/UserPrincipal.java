package br.com.scriptping.security;

import br.com.scriptping.users.AppUser;
import java.io.Serializable;

public record UserPrincipal(Long id, String username, String name, long sessionVersion) implements Serializable {
    public static UserPrincipal from(AppUser user) {
        return new UserPrincipal(user.id(), user.username(), user.name(), user.sessionVersion());
    }
}
