package br.com.scriptping.security;

import br.com.scriptping.users.*;
import br.com.scriptping.zabbix.ZabbixClient;
import br.com.scriptping.ingestion.CollectorClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:security-flow;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "collector.enabled=false", "zabbix.url=", "zabbix.username=", "zabbix.password=", "indicators.aggregation.initial-delay-ms=3600000", "indicators.availability.initial-delay-ms=3600000"})
@AutoConfigureMockMvc
class SecurityFlowTests {
    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired UserRepository repository;
    @MockitoBean ZabbixClient zabbix;
    @MockitoBean CollectorClient collector;
    private UserService.Saved create(AppUser.Role role) {
        return users.save(null, new UserService.Input("José da Silva", "user" + UUID.randomUUID().toString().substring(0, 8), role, null));
    }
    private MockHttpSession identify(String username) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/login/usuario").session(session).with(csrf()).param("username", username))
            .andExpect(status().is3xxRedirection());
        return session;
    }
    private MockHttpSession activate(UserService.Saved saved) throws Exception {
        var session = identify(saved.user().username());
        mvc.perform(post("/login/nova-senha").session(session).with(csrf()).param("code", saved.activationCode())
            .param("password", "SenhaForte#12345").param("confirmation", "SenhaForte#12345"))
            .andExpect(redirectedUrl("/disponibilidade"));
        return session;
    }
    @Test void progressiveLoginReusesChallengeAndRetainsItAfterWrongPassword() throws Exception {
        var saved = create(AppUser.Role.USER);
        activate(saved);
        var session = new MockHttpSession();
        mvc.perform(post("/login/usuario").session(session).with(csrf()).accept("application/json")
            .param("username", saved.user().username()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.setup").value(false));
        mvc.perform(post("/login/senha").session(session).with(csrf()).accept("application/json")
            .param("password", "wrong"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        mvc.perform(post("/login/senha").session(session).with(csrf()).accept("application/json")
            .param("password", "SenhaForte#12345"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.redirect").value("/disponibilidade"));
        mvc.perform(get("/api/maps").session(session)).andExpect(status().isOk());
    }
    @Test void progressiveIdentificationSupportsSetupNeutralErrorsAndClear() throws Exception {
        var saved = create(AppUser.Role.USER);
        var session = new MockHttpSession();
        mvc.perform(post("/login/usuario").session(session).with(csrf()).accept("application/json")
            .param("username", saved.user().username()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.setup").value(true));
        mvc.perform(post("/login/limpar").session(session).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/login/senha").session(session).with(csrf()).accept("application/json")
            .param("password", "wrong"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.reset").value(true));
        mvc.perform(post("/login/usuario").session(session).with(csrf()).accept("application/json")
            .param("username", "missing-" + UUID.randomUUID()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(
                "Não foi possível continuar. Confira os dados ou aguarde antes de tentar novamente."));
        mvc.perform(post("/login/usuario").accept("application/json").param("username", "admin"))
            .andExpect(status().isForbidden());
    }
    @Test void activationValidationDoesNotConsumeCodeAndCompletionRechecksIt() throws Exception {
        var saved = create(AppUser.Role.USER);
        var session = identify(saved.user().username());
        mvc.perform(get("/login/nova-senha").session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("data-setup=\"true\"")));
        mvc.perform(post("/login/validar-codigo").session(session).with(csrf()).param("code", "wrong"))
            .andExpect(status().isBadRequest());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/login/validar-codigo").session(session).with(csrf()).param("code", saved.activationCode()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.validated").value(true));
        }
        assertThat(repository.findById(saved.user().id()).orElseThrow().needsPassword()).isTrue();
        mvc.perform(get("/api/maps").session(session)).andExpect(status().isUnauthorized());
        users.reset(saved.user().id());
        mvc.perform(post("/login/nova-senha").session(session).with(csrf()).accept("application/json")
            .param("code", saved.activationCode()).param("password", "SenhaForte#12345")
            .param("confirmation", "SenhaForte#12345"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codeInvalid").value(true));
        mvc.perform(post("/login/validar-codigo").with(csrf()).param("code", saved.activationCode()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.reset").value(true));
        mvc.perform(post("/login/validar-codigo").session(session).param("code", saved.activationCode()))
            .andExpect(status().isForbidden());
    }
    @Test void anonymousCannotAccessPagesOrApisOrSkipIdentification() throws Exception {
        mvc.perform(get("/disponibilidade")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/api/maps")).andExpect(status().isUnauthorized());
        mvc.perform(get("/login/senha")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/login/nova-senha")).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/login/nova-senha").with(csrf()).param("password", "SenhaForte#12345"))
            .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(get("/auth.css")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
    @Test void firstAccessRequiresCodeAndStrongMatchingPasswordThenUsesPasswordLogin() throws Exception {
        var saved = create(AppUser.Role.USER);
        assertThat(repository.findById(saved.user().id()).orElseThrow().passwordHash()).isNull();
        var session = identify(saved.user().username().toUpperCase());
        mvc.perform(get("/login/senha").session(session)).andExpect(redirectedUrl("/login/nova-senha"));
        mvc.perform(get("/login/nova-senha").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/maps").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(post("/login/nova-senha").session(session).with(csrf()).param("code", "wrong")
            .param("password", "SenhaForte#12345").param("confirmation", "SenhaForte#12345"))
            .andExpect(view().name("password-setup")).andExpect(model().attributeExists("error"));
        for (var password : new String[]{"Curta#1", "senhasemcaixa#123", "SEMMINUSCULA#123", "SemNumeros#Senha", "SemEspecial12345"}) {
            mvc.perform(post("/login/nova-senha").session(session).with(csrf()).param("code", saved.activationCode())
                .param("password", password).param("confirmation", password))
                .andExpect(view().name("password-setup")).andExpect(model().attributeExists("error"));
        }
        mvc.perform(post("/login/nova-senha").session(session).with(csrf()).param("code", saved.activationCode())
            .param("password", "SenhaForte#12345").param("confirmation", "Different#12345"))
            .andExpect(model().attributeExists("error"));
        mvc.perform(post("/login/nova-senha").session(session).with(csrf()).param("code", saved.activationCode())
            .param("password", "SenhaForte#12345").param("confirmation", "SenhaForte#12345"))
            .andExpect(redirectedUrl("/disponibilidade"));
        assertThat(repository.findById(saved.user().id()).orElseThrow().passwordHash()).doesNotContain("SenhaForte");
        mvc.perform(get("/api/maps").session(session)).andExpect(status().isOk());
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(redirectedUrl("/login?logout"));
        var login = identify(saved.user().username());
        mvc.perform(get("/login/nova-senha").session(login)).andExpect(redirectedUrl("/login/senha"));
        mvc.perform(get("/login/senha").session(login)).andExpect(status().isOk());
        mvc.perform(post("/login/senha").session(login).with(csrf()).param("password", "WrongPassword#123"))
            .andExpect(model().attributeExists("error"));
        mvc.perform(post("/login/senha").session(login).with(csrf()).param("password", "SenhaForte#12345"))
            .andExpect(redirectedUrl("/disponibilidade"));
    }
    @Test void normalUserCanViewMapsButCannotWriteImportOrAccessSettings() throws Exception {
        var saved = create(AppUser.Role.USER); var session = activate(saved);
        mvc.perform(get("/mapas").session(session)).andExpect(status().isOk())
            .andExpect(content().string(not(containsString("href=\"/configuracao/usuarios\""))))
            .andExpect(content().string(containsString("name=\"is-admin\" content=\"false\"")));
        mvc.perform(get("/ap").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/maps").session(session)).andExpect(status().isOk());
        for (String url : new String[]{"/configuracao/usuarios", "/configuracao/zabbix", "/api/users", "/api/ap/candidates"})
            mvc.perform(get(url).session(session)).andExpect(status().isForbidden());
        for (String url : new String[]{"/api/maps", "/api/maps/", "/api/ap", "/api/users"})
            mvc.perform(post(url).session(session).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/users/1").session(session).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/maps/states").session(session).with(csrf()).contentType("application/json").content("{\"groups\":[],\"hosts\":[]}"))
            .andExpect(status().isOk());
    }
    @Test void resetRevokesSessionAndOldCodeAndPassword() throws Exception {
        var saved = create(AppUser.Role.USER); var session = activate(saved);
        var reset = users.reset(saved.user().id());
        mvc.perform(get("/api/maps").session(session)).andExpect(status().isUnauthorized());
        var next = identify(saved.user().username());
        mvc.perform(post("/login/senha").session(next).with(csrf()).param("password", "SenhaForte#12345"))
            .andExpect(redirectedUrl("/login"));
        mvc.perform(post("/login/nova-senha").session(next).with(csrf()).param("code", saved.activationCode())
            .param("password", "NovaSenha#12345").param("confirmation", "NovaSenha#12345"))
            .andExpect(model().attributeExists("error"));
        mvc.perform(post("/login/nova-senha").session(next).with(csrf()).param("code", reset.activationCode())
            .param("password", "NovaSenha#12345").param("confirmation", "NovaSenha#12345"))
            .andExpect(redirectedUrl("/disponibilidade"));
        assertThat(repository.findById(saved.user().id()).orElseThrow().needsPassword()).isFalse();
    }
    @Test @WithMockUser(roles = "ADMIN")
    void adminCanManageUsersAndCsrfIsRequired() throws Exception {
        mvc.perform(get("/configuracao/usuarios")).andExpect(status().isOk())
            .andExpect(content().string(containsString("Novo usuário"))).andExpect(content().string(containsString("Usuário normal")));
        mvc.perform(post("/api/users").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/users").with(csrf()).contentType("application/json")
            .content("{\"name\":\"Maria\",\"username\":\"maria\",\"role\":\"USER\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.activationCode").isString())
            .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
        mvc.perform(get("/api/users")).andExpect(status().isOk())
            .andExpect(content().string(not(containsString("activationHash"))))
            .andExpect(content().string(not(containsString("passwordHash"))));
        mvc.perform(post("/api/users").with(csrf()).contentType("application/json")
            .content("{\"name\":\"Maria 2\",\"username\":\"MARIA\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isBadRequest());
    }
    @Test void emailUsernameCanBeCreatedActivatedAndUsedForLogin() throws Exception {
        String username = "dioni.alves@getel.net.br";
        String response = mvc.perform(post("/api/users").with(user("admin").roles("ADMIN")).with(csrf())
            .contentType("application/json")
            .content("{\"name\":\"Dioni Alves de Oliveira\",\"username\":\"DIONI.ALVES@GETEL.NET.BR\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.username").value(username))
            .andReturn().getResponse().getContentAsString();
        String code = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("activationCode").asText();
        mvc.perform(post("/api/users").with(user("admin").roles("ADMIN")).with(csrf())
            .contentType("application/json")
            .content("{\"name\":\"Outro cadastro\",\"username\":\"dioni.alves@getel.net.br\",\"role\":\"USER\"}"))
            .andExpect(status().isBadRequest());
        var firstAccess = identify(username.toUpperCase());
        mvc.perform(post("/login/nova-senha").session(firstAccess).with(csrf()).param("code", code)
            .param("password", "SenhaForte#12345").param("confirmation", "SenhaForte#12345"))
            .andExpect(redirectedUrl("/disponibilidade"));
        mvc.perform(post("/logout").session(firstAccess).with(csrf())).andExpect(redirectedUrl("/login?logout"));
        var login = identify(username);
        mvc.perform(get("/login/senha").session(login)).andExpect(status().isOk());
        mvc.perform(post("/login/senha").session(login).with(csrf()).param("password", "SenhaForte#12345"))
            .andExpect(redirectedUrl("/disponibilidade"));
        mvc.perform(get("/api/users").session(login)).andExpect(status().isOk());
        var created = users.list().stream().filter(u -> u.username().equals(username)).findFirst().orElseThrow();
        users.save(created.id(), new UserService.Input(created.name(), username, AppUser.Role.USER, created.version()));
        assertThatCode(() -> users.save(null, new UserService.Input("Alias", "dioni.alves+teste@getel.net.br", AppUser.Role.USER, null)))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> users.save(null, new UserService.Input("Inválido", "usuario com espaco", AppUser.Role.USER, null)))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void cannotDemoteLastAdministratorAndStaleEditsAreRejected() {
        var admin = users.list().stream().filter(u -> u.username().equals("admin")).findFirst().orElseThrow();
        assertThatThrownBy(() -> users.save(admin.id(), new UserService.Input(admin.name(), admin.username(), AppUser.Role.USER, admin.version())))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("pelo menos um");
        var saved = create(AppUser.Role.USER);
        users.save(saved.user().id(), new UserService.Input("Nome novo", saved.user().username(), AppUser.Role.USER, saved.user().version()));
        assertThatThrownBy(() -> users.save(saved.user().id(), new UserService.Input("Nome antigo", saved.user().username(), AppUser.Role.USER, saved.user().version())))
            .hasMessageContaining("Cadastro alterado");
    }
    @Test void profileChangeRevokesPreviousPermissionsAndBootstrapDoesNotOverwritePassword() throws Exception {
        var saved = create(AppUser.Role.ADMIN); var session = activate(saved);
        var view = users.list().stream().filter(u -> u.id().equals(saved.user().id())).findFirst().orElseThrow();
        users.save(view.id(), new UserService.Input(view.name(), view.username(), AppUser.Role.USER, view.version()));
        mvc.perform(get("/api/users").session(session)).andExpect(status().isUnauthorized());
        var admin = repository.findByUsername("admin").orElseThrow();
        String code = users.bootstrap();
        users.setPassword(admin.id(), admin.sessionVersion(), code, "AdminSenha#12345", "AdminSenha#12345");
        var hash = repository.findByUsername("admin").orElseThrow().passwordHash();
        assertThat(users.bootstrap()).isNull();
        assertThat(repository.findByUsername("admin").orElseThrow().passwordHash()).isEqualTo(hash);
    }
}
