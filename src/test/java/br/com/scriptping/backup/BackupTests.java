package br.com.scriptping.backup;

import br.com.scriptping.hosts.HostRepository;
import br.com.scriptping.hosts.HostSecrets;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackupTests {
    @TempDir Path directory;

    @Test
    void scheduledBackupContinuesAfterFailureAndSharesManualExecutionGuard() throws Exception {
        var store = mock(BackupStore.class);
        var capture = mock(BackupCapture.class);
        var jobs = new BackupJobs(store, capture);
        var result = new BackupCapture.Result("Switch", "Datacom", ".cfg", new byte[0], true);
        when(store.inventory()).thenReturn(List.of(
            new BackupStore.HostOption("1", "Switch 1", "", List.of(), true, true),
            new BackupStore.HostOption("2", "Switch 2", "", List.of(), true, true),
            new BackupStore.HostOption("3", "Switch 3", "", List.of(), false, true),
            new BackupStore.HostOption("4", "Switch 4", "", List.of(), true, false)));
        when(capture.capture(eq("1"), any())).thenAnswer(invocation -> {
            assertThatThrownBy(() -> jobs.start("2")).hasMessageContaining("Já existe um backup em execução");
            throw new java.io.IOException("Falha de conexão.");
        });
        when(capture.capture(eq("2"), any())).thenReturn(result);

        var details = new java.util.ArrayList<br.com.scriptping.scheduling.ScheduledTask.Detail>();
        try {
            var outcome = new HostBackupTask(jobs).execute(Map.of(),
                new br.com.scriptping.scheduling.ScheduledTask.Context("execution", details::add));
            assertThat(outcome.partial()).isTrue();
            assertThat(jobs.status().running()).isFalse();
            assertThat(details).extracting(detail -> detail.hostId() + ":" + detail.state())
                .containsExactly("1:PENDING", "2:PENDING", "3:PENDING", "1:RUNNING", "1:FAILED",
                    "2:RUNNING", "2:COMPLETED", "3:RUNNING", "3:FAILED");
            var order = inOrder(capture, store);
            order.verify(capture).capture(eq("1"), any());
            order.verify(capture).capture(eq("2"), any());
            order.verify(store).insert("2", result);
            verifyNoMoreInteractions(capture);
        } finally {
            jobs.close();
        }
    }

    @Test
    void changesHostStatusOnlyWhenSshRejectsAuthentication() throws Exception {
        var hosts = org.mockito.Mockito.mock(HostRepository.class);
        var connections = org.mockito.Mockito.mock(br.com.scriptping.hosts.HostConnections.class);
        var ssh = org.mockito.Mockito.mock(br.com.scriptping.ubiquiti.UbiquitiSsh.class);
        var capture = new BackupCapture(hosts, connections, ssh, null, null, 120);
        var host = new br.com.scriptping.zabbix.Host("1", "Switch", "192.0.2.10", List.of());
        var saved = new HostRepository.Credentials("Datacom", "backup", "encrypted", null, true,
            "ACTIVE", null, host.ip(), false, 1L);
        var connection = new br.com.scriptping.equipment.EquipmentProvider.Connection("backup", "password", 2222);
        when(hosts.target("1")).thenReturn(host);
        when(hosts.credentials("1")).thenReturn(saved);
        when(hosts.terminalPorts("1")).thenReturn(new HostRepository.TerminalPorts(2222, 23));
        when(connections.require(host, saved)).thenReturn(connection);
        when(ssh.connect(host.ip(), connection))
            .thenThrow(new com.jcraft.jsch.JSchException("Connection timed out"))
            .thenThrow(new com.jcraft.jsch.JSchException("Auth fail for methods 'password'"));

        var events = new java.util.ArrayList<String>();
        assertThatThrownBy(() -> capture.capture("1", events::add))
            .hasMessageContaining("Tempo limite").hasMessageContaining("SSH na porta 2222");
        assertThat(events).containsExactly("Conectando ao host via SSH na porta 2222");
        verify(hosts, never()).markAuthenticationRejected(anyString(), any(), any(), anyString());
        assertThatThrownBy(() -> capture.capture("1", message -> {})).hasMessageContaining("Credenciais rejeitadas");
        verify(hosts).markAuthenticationRejected(eq("1"), eq(saved), eq(2222), contains("Credenciais rejeitadas"));
    }

    @Test
    void keepsMostRecentBackupsPerHostAndPreservesOriginalBytes() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:backup-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        schema.execute(source);
        schema.execute(source);
        var jdbc = new JdbcTemplate(source);
        var json = new ObjectMapper();
        var secrets = new HostSecrets(directory.resolve("key").toString());
        var store = new BackupStore(jdbc, new HostRepository(jdbc, json, secrets), secrets);
        jdbc.update("INSERT INTO app_host(host_id,payload,synced_at) VALUES ('1','{}',0),('2','{}',0)");
        assertThat(store.settings("1")).isEqualTo(new BackupStore.Settings(false, 5));

        jdbc.update("INSERT INTO backup_settings(id, retention, host_ids) VALUES (1, ?, ?)", 3, json.writeValueAsString(List.of("1")));
        var migration = new BackupSettingsMigration(jdbc, new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        migration.afterPropertiesSet();
        assertThat(store.settings("1")).isEqualTo(new BackupStore.Settings(true, 3));
        assertThat(store.settings("2")).isEqualTo(new BackupStore.Settings(false, 3));

        byte[] original = {0, 12, (byte) 0xff};
        byte[] text = "interface VLAN".getBytes(StandardCharsets.UTF_8);
        var digistar = new BackupCapture.Result("OLT", "Digistar", ".tar", original, false);
        var datacom = new BackupCapture.Result("Switch", "Datacom", ".cfg", text, true);
        store.insert("1", digistar);
        jdbc.update("UPDATE equipment_backup SET created_at=1");
        store.insert("1", digistar);
        store.insert("2", datacom);
        var oldBackup = jdbc.queryForObject("SELECT id FROM equipment_backup WHERE created_at=1", String.class);

        jdbc.update("UPDATE equipment_backup SET created_at=2 WHERE host_id='2'");
        store.insert("2", datacom);

        store.save("1", new BackupStore.Settings(false, 1));
        schema.execute(source);
        migration.afterPropertiesSet();
        assertThat(store.settings("1")).isEqualTo(new BackupStore.Settings(false, 1));
        assertThat(store.settings("2")).isEqualTo(new BackupStore.Settings(false, 3));
        var history = store.list();
        assertThat(history).extracting(BackupStore.Entry::hostId).containsExactlyInAnyOrder("1", "2", "2");
        assertThat(history).extracting(BackupStore.Entry::id).doesNotContain(oldBackup);
        var file = store.file(history.stream().filter(entry -> entry.hostId().equals("1")).findFirst().orElseThrow().id());
        assertThat(file.entry().filename()).endsWith(".tar");
        assertThat(file.entry().text()).isFalse();
        assertThat(file.content()).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT encrypted_content FROM equipment_backup WHERE host_id='2' AND created_at=2", String.class))
            .doesNotContain("interface VLAN");
        store.delete(file.entry().id());
        assertThat(store.list()).hasSize(2);
        assertThat(BackupCapture.textContent(text)).isEqualTo("interface VLAN");
        assertThat(BackupCapture.textContent(original)).isNull();
    }
}
