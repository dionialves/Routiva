package br.com.scriptping.security;

import br.com.scriptping.users.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.*;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean org.springframework.security.core.userdetails.UserDetailsService userDetailsService(UserRepository users) {
        return username -> {
            var user = users.findByUsername(UserService.normalize(username)).orElseThrow(() ->
                new org.springframework.security.core.userdetails.UsernameNotFoundException("Usuário não encontrado."));
            return org.springframework.security.core.userdetails.User.withUsername(user.username())
                .password(user.passwordHash() == null ? "" : user.passwordHash())
                .roles(user.role().name()).disabled(user.needsPassword()).build();
        };
    }
    @Bean ApplicationRunner bootstrapAdmin(UserService users) {
        return args -> {
            String code = users.bootstrap();
            if (code != null) LoggerFactory.getLogger(SecurityConfiguration.class)
                .warn("Primeiro acesso: usuário admin. Código de ativação (válido por 24h): {}", code);
        };
    }
    @Bean SecurityFilterChain security(HttpSecurity http, UserRepository users, SecurityContextRepository contexts) throws Exception {
        http.securityContext(context -> context.securityContextRepository(contexts))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/login/**", "/error", "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/*.css", "/*.js", "/*.svg", "/*.png", "/*.ico", "/*.woff2", "/webjars/**").permitAll()
                .requestMatchers("/configuracao/**", "/api/users/**", "/api/ap/candidates").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/ap", "/api/ap/", "/api/maps", "/api/maps/").hasRole("ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
                if (request.getRequestURI().startsWith("/api/")) {
                    response.setStatus(401); response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"Sua sessão expirou. Entre novamente.\"}");
                } else response.sendRedirect("/login");
            }).accessDeniedHandler((request, response, exception) -> {
                response.setStatus(403); response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"message\":\"Acesso não permitido ou sessão de formulário expirada. Atualize a página.\"}");
            }))
            .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout").invalidateHttpSession(true).deleteCookies("JSESSIONID"))
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                    var auth = SecurityContextHolder.getContext().getAuthentication();
                    if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
                        var user = users.findById(principal.id()).orElse(null);
                        if (user == null || user.needsPassword() || user.sessionVersion() != principal.sessionVersion()) {
                            SecurityContextHolder.clearContext();
                            var session = request.getSession(false);
                            if (session != null) session.invalidate();
                        }
                    }
                    response.setHeader("Cache-Control", "no-store");
                    chain.doFilter(request, response);
                }
            }, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
