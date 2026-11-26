package br.com.scriptping.hosts;

import br.com.scriptping.zabbix.*;
import br.com.scriptping.equipment.EquipmentProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostCatalogTests {
    @TempDir java.nio.file.Path temp;
    JdbcTemplate jdbc;
    HostRepository hosts;
    HostCatalog catalog;
    HostSecrets secrets;
    ZabbixClient client;
    ZabbixSettings settings;
    ManagedHost host(String id) {
        return new ManagedHost(id, "router-" + id, "Router " + id, List.of(new ManagedHost.Choice("1", "Rede")),
            List.of(), "5", "192.0.2." + id, "", true, "161", 2, "community-secret", true, "revision", true, 1, 1, "available", "Observação do host");
    }
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        secrets = new HostSecrets(temp.resolve("key").toString());
        hosts = new HostRepository(jdbc, new ObjectMapper(), secrets);
        client = mock(ZabbixClient.class); settings = mock(ZabbixSettings.class);
        when(settings.current()).thenReturn(new ZabbixSettings.State("https://zabbix.test", "api", "secret", Set.of(), Set.of(), List.of(), null));
        when(client.configured()).thenReturn(true);
        when(client.managedHosts()).thenReturn(List.of(host("42")));
        catalog = new HostCatalog(hosts, client, settings, new DataSourceTransactionManager(source));
    }
    @Test void migrationRestoresLegacyHostsLocallyEvenWhenZabbixIsOffline() {
        var old = new Host("42", "AP", "192.0.2.42", List.of());
        when(settings.current()).thenReturn(new ZabbixSettings.State("https://zabbix.test", "api", "secret", Set.of(), Set.of("42"), List.of(old), 1L));
        clearInvocations(client);
        catalog.restoreLegacy();
        assertThat(hosts.target("42")).isEqualTo(old);
        assertThat(hosts.require("42").editable()).isFalse();
        verifyNoInteractions(client);
        catalog.reconcile();
        assertThat(hosts.require("42").editable()).isTrue();
    }
    @Test void firstSyncAlsoRemovesLegacyApWhenHostWasAlreadyDeletedInZabbix() {
        jdbc.update("INSERT INTO access_point(host_id,host_name,created_at,updated_at) VALUES ('99','Removed',1,1)");
        catalog.reconcile();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_point", Integer.class)).isZero();
    }
    @Test void connectionStatusReflectsSavedValidationAndAddressChanges() {
        var h = host("42");
        assertThat(HostAuthentication.connectionStatus(h, null)).isEqualTo("NOT_CONFIGURED");
        var valid = new HostRepository.Credentials("MikroTik", "reader", "encrypted", 8728, true, "ACTIVE", "", h.ip(), false, 1L);
        assertThat(HostAuthentication.connectionStatus(h, valid)).isEqualTo("ACTIVE");
        var stale = new HostRepository.Credentials("MikroTik", "reader", "encrypted", 8728, true, "ACTIVE", "", "192.0.2.99", false, 1L);
        assertThat(HostAuthentication.connectionStatus(h, stale)).isEqualTo("ERROR");
        var failed = new HostRepository.Credentials("MikroTik", "reader", "encrypted", 8728, true, "CONNECTION_FAILED", "", null, false, 1L);
        assertThat(HostAuthentication.connectionStatus(h, failed)).isEqualTo("ERROR");
    }
    @Test void readsLocallyAfterRestartAndPreservesAuthenticationAcrossRemoteUpdates() {
        catalog.reconcile();
        hosts.configure("42", new HostRepository.Credentials("MikroTik", "reader", secrets.encrypt("equipment-secret"), 8728, true, "ACTIVE", "", "192.0.2.42", false, 1L));
        catalog.reconcile(); clearInvocations(client);
        var restarted = new HostRepository(jdbc, new ObjectMapper(), secrets);
        assertThat(restarted.require("42")).isEqualTo(host("42"));
        assertThat(jdbc.queryForObject("SELECT description FROM app_host WHERE host_id='42'", String.class)).isEqualTo("Observação do host");
        assertThat(secrets.decrypt(restarted.credentials("42").encryptedPassword())).isEqualTo("equipment-secret");
        catalog.options(); catalog.all(); verifyNoInteractions(client);
        assertThat(jdbc.queryForObject("SELECT payload FROM app_host WHERE host_id='42'", String.class)).doesNotContain("community-secret");
    }
    @Test void remoteDeletionRemovesHostApAuthenticationConnectionsAndMapReferences() {
        catalog.reconcile();
        jdbc.update("INSERT INTO access_point(host_id,host_name,created_at,updated_at) VALUES ('42','AP',1,1)");
        jdbc.update("INSERT INTO network_map(id,revision,layout) VALUES ('map',1,?)", "{\"nodes\":[{\"id\":\"a\",\"hostId\":\"42\"},{\"id\":\"b\",\"hostId\":\"43\"}],\"links\":[{\"from\":\"a\",\"to\":\"b\"}]}");
        when(client.managedHosts()).thenReturn(List.of()); catalog.reconcile();
        assertThat(hosts.all()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_point", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT layout FROM network_map", String.class)).doesNotContain("42").contains("43");
        assertThat(jdbc.queryForObject("SELECT revision FROM network_map", Long.class)).isEqualTo(2);
    }
    @Test void remoteReadFailureNeverDeletesLocalHosts() {
        catalog.reconcile();
        when(client.hostTemplates()).thenThrow(new IllegalStateException("offline"));
        when(client.managedHosts()).thenReturn(List.of());
        assertThatThrownBy(catalog::reconcile).isInstanceOf(IllegalStateException.class);
        assertThat(hosts.all()).containsExactly(host("42"));
    }
    @Test void localDeletionRequiresRemoteConfirmation() {
        catalog.reconcile();
        doThrow(new IllegalStateException("offline")).when(client).deleteManagedHost("42", "revision");
        assertThatThrownBy(() -> catalog.delete("42", "revision")).isInstanceOf(IllegalStateException.class);
        assertThat(hosts.all()).hasSize(1);
        doNothing().when(client).deleteManagedHost("42", "revision"); catalog.delete("42", "revision");
        assertThat(hosts.all()).isEmpty(); verify(client, times(2)).deleteManagedHost("42", "revision");
    }
    @Test void terminalPortsPersistIndependentlyOfEquipmentCredentialsAndInventorySync() {
        var host = host("42");
        hosts.put(host);
        var auth = new HostAuthentication(hosts, secrets, List.of());
        assertThat(auth.terminalAccess(host.id())).isEqualTo(new HostAuthentication.TerminalAccess(host.ip(), 22, 23));

        auth.saveTerminalPorts(host.id(), new HostRepository.TerminalPorts(2222, 2323));
        catalog.reconcile();
        assertThat(hosts.credentials(host.id())).isNull();
        assertThat(auth.terminalAccess(host.id())).isEqualTo(new HostAuthentication.TerminalAccess(host.ip(), 2222, 2323));
        assertThatThrownBy(() -> auth.saveTerminalPorts(host.id(), new HostRepository.TerminalPorts(0, 23)))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(auth.view(host.id()).sshPort()).isEqualTo(2222);
        assertThat(auth.view(host.id()).telnetPort()).isEqualTo(2323);
    }

    @Test void sshIntegrationUsesSharedPortForTestSaveAndSubsequentConnections() {
        catalog.reconcile();
        var provider = mock(EquipmentProvider.class);
        when(provider.manufacturer()).thenReturn("Cisco");
        var auth = new HostAuthentication(hosts, secrets, List.of(provider));
        var input = new HostAuthentication.Input("Cisco", "reader", "secret", 8728, true, false, 2222, 2323);
        assertThat(auth.test("42", input).success()).isTrue();
        assertThat(auth.terminalAccess("42").sshPort()).isEqualTo(22);
        var view = auth.save("42", input);
        assertThat(view.port()).isNull();
        assertThat(view.sshPort()).isEqualTo(2222);
        assertThat(view.telnetPort()).isEqualTo(2323);
        verify(provider, times(2)).testConnection(any(), argThat(connection -> connection.port() == 2222));
        var connections = new HostConnections(hosts, secrets);
        assertThat(connections.require(hosts.target("42")).port()).isEqualTo(2222);
        auth.saveTerminalPorts("42", new HostRepository.TerminalPorts(2223, 2323));
        assertThat(auth.view("42").status()).isEqualTo("CONNECTION_FAILED");
        auth.validateSaved("42");
        assertThat(connections.require(hosts.target("42")).port()).isEqualTo(2223);
    }

    @Test void legacySshIntegrationPortIsMigratedWithoutOverwritingLaterTerminalEdits() {
        catalog.reconcile();
        hosts.configure("42", new HostRepository.Credentials("Cisco", "reader", secrets.encrypt("secret"),
            2222, true, "ACTIVE", "", "192.0.2.42", false, 1L));
        var populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        populator.execute(jdbc.getDataSource());
        assertThat(hosts.terminalPorts("42").sshPort()).isEqualTo(2222);
        assertThat(hosts.credentials("42").port()).isNull();
        hosts.updateTerminalPorts("42", new HostRepository.TerminalPorts(22, 23));
        populator.execute(jdbc.getDataSource());
        assertThat(hosts.terminalPorts("42").sshPort()).isEqualTo(22);
    }

    @Test void authenticationWorksWithoutApAndRejectsChangedAddress() {
        catalog.reconcile();
        var provider = mock(EquipmentProvider.class); when(provider.manufacturer()).thenReturn("MikroTik");
        var auth = new HostAuthentication(hosts, secrets, List.of(provider));
        var input = new HostAuthentication.Input("MikroTik", "reader", "private-secret", 8728, true, false);
        assertThat(auth.test("42", input).success()).isTrue();
        assertThat(hosts.credentials("42")).isNull();
        assertThat(auth.save("42", input).status()).isEqualTo("ACTIVE");
        clearInvocations(provider);
        var withoutPassword = new HostAuthentication.Input("MikroTik", "reader", null, 8728, true, false);
        assertThat(auth.test("42", withoutPassword).success()).isTrue();
        assertThat(auth.save("42", withoutPassword).status()).isEqualTo("ACTIVE");
        verify(provider, times(2)).testConnection(hosts.target("42"),
            new EquipmentProvider.Connection("reader", "private-secret", 8728, false));
        var connections = new HostConnections(hosts, secrets);
        assertThat(connections.require(hosts.target("42")).password()).isEqualTo("private-secret");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_point", Integer.class)).isZero();
        var changed = new Host("42", "Router", "192.0.2.99", List.of());
        assertThatThrownBy(() -> connections.require(changed)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var saved = hosts.credentials("42");
        doThrow(new IllegalStateException("Credenciais rejeitadas")).when(provider).testConnection(any(), any());
        assertThat(auth.test("42", withoutPassword).success()).isFalse();
        assertThat(auth.connectionStatuses(hosts.all())).containsEntry("42", "ERROR");
        assertThat(hosts.credentials("42").encryptedPassword()).isEqualTo(saved.encryptedPassword());
        var rejected = new HostAuthentication.Input("MikroTik", "new-reader", "new-secret", 8729, true, true);
        assertThat(auth.save("42", rejected).status()).isEqualTo("CONNECTION_FAILED");
        assertThat(hosts.credentials("42").login()).isEqualTo("new-reader");
        assertThat(secrets.decrypt(hosts.credentials("42").encryptedPassword())).isEqualTo("new-secret");
    }
    @Test void savingWithoutManufacturerRemovesCredentialsWithoutTestingConnection() {
        catalog.reconcile();
        var provider = mock(EquipmentProvider.class);
        when(provider.manufacturer()).thenReturn("MikroTik");
        var auth = new HostAuthentication(hosts, secrets, List.of(provider));
        auth.save("42", new HostAuthentication.Input("MikroTik", "reader", "secret", 8728, true, false));
        clearInvocations(provider);

        var view = auth.save("42", new HostAuthentication.Input("", "", null, null, false, false, 2222, 2323));

        verifyNoInteractions(provider);
        assertThat(hosts.credentials("42")).isNull();
        assertThat(view.manufacturer()).isNull();
        assertThat(view.login()).isNull();
        assertThat(view.passwordConfigured()).isFalse();
        assertThat(view.sshPort()).isEqualTo(2222);
        assertThat(view.telnetPort()).isEqualTo(2323);
        assertThat(auth.connectionStatuses(hosts.all())).containsEntry("42", "NOT_CONFIGURED");
    }

    @Test void unreadableSavedPasswordAllowsSavingOtherFieldsAndExplicitPasswordCanReplaceIt() {
        catalog.reconcile();
        var provider = mock(EquipmentProvider.class);
        when(provider.manufacturer()).thenReturn("MikroTik");
        var auth = new HostAuthentication(hosts, secrets, List.of(provider));
        var input = new HostAuthentication.Input("MikroTik", "reader", "secret", 8728, true, false);
        auth.save("42", input);
        var saved = hosts.credentials("42");
        var replacementKey = new HostSecrets(temp.resolve("replacement-key").toString());
        replacementKey.encrypt("initialize");
        var afterKeyChange = new HostAuthentication(hosts, replacementKey, List.of(provider));
        var blank = new HostAuthentication.Input("MikroTik", "reader", "", 8728, true, false);
        clearInvocations(provider);

        assertThatThrownBy(() -> afterKeyChange.test("42", blank))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("Não foi possível ler a senha cadastrada");
        var changed = new HostAuthentication.Input("MikroTik", "new-reader", "", 8729, true, true);
        var view = afterKeyChange.save("42", changed);
        assertThat(view.message()).contains("Não foi possível ler a senha cadastrada");
        assertThat(view.login()).isEqualTo("new-reader");
        assertThat(view.port()).isEqualTo(8729);
        verify(provider, never()).testConnection(any(), any());
        var failed = hosts.credentials("42");
        assertThat(failed.status()).isEqualTo("CONNECTION_FAILED");
        assertThat(failed.encryptedPassword()).isEqualTo(saved.encryptedPassword());
        assertThat(afterKeyChange.test("42", input).success()).isTrue();
        assertThat(hosts.credentials("42")).isEqualTo(failed);
        assertThat(afterKeyChange.save("42", input).status()).isEqualTo("ACTIVE");
        assertThat(afterKeyChange.test("42", blank).success()).isTrue();
    }
    @Test void mimosaUsesFixedLoginEvenWhenClientOmitsOrMisspellsIt() {
        catalog.reconcile();
        var provider = mock(EquipmentProvider.class); when(provider.manufacturer()).thenReturn("Mimosa");
        var auth = new HostAuthentication(hosts, secrets, List.of(provider));
        for (String login : new String[]{null, "confihgure"}) {
            var view = auth.save("42", new HostAuthentication.Input("Mimosa", login, "test-secret", 443, true, false));
            assertThat(view.login()).isEqualTo("configure");
            assertThat(view.tls()).isTrue();
            assertThat(view.status()).isEqualTo("ACTIVE");
        }
        verify(provider, times(2)).testConnection(any(), argThat(connection -> connection.login().equals("configure") && connection.tls()));
    }
    @Test void migratingApSecretsIsRepeatableAndDoesNotOverwriteHostCredentials() {
        jdbc.update("INSERT INTO access_point(host_id,host_name,created_at,updated_at,manufacturer,api_login,api_password,api_port) VALUES ('42','AP',1,1,'MikroTik','legacy',?,8728)", secrets.encrypt("legacy-secret"));
        var populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        populator.execute(jdbc.getDataSource()); populator.execute(jdbc.getDataSource());
        assertThat(secrets.decrypt(hosts.credentials("42").encryptedPassword())).isEqualTo("legacy-secret");
        assertThat(jdbc.queryForObject("SELECT api_password FROM access_point WHERE host_id='42'", String.class)).isNull();
    }
}
