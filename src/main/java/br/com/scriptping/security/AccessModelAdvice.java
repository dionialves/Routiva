package br.com.scriptping.security;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@ControllerAdvice
public class AccessModelAdvice {
    @ModelAttribute("isAdmin")
    public boolean isAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
    @ModelAttribute("currentUserName")
    public String currentUserName(Authentication auth) {
        if (auth == null) return "";
        return auth.getPrincipal() instanceof UserPrincipal user ? user.name() : auth.getName();
    }
}
