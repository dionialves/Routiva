package br.com.scriptping;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ZabbixSettingsPersistenceTests {
    @Test
    void emptyDatabaseUsesEnvironmentDefaults() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        var settings = new ZabbixSettings(jdbc, new ObjectMapper(), "url", "user", "password");
        assertThat(settings.current()).isEqualTo(new ZabbixSettings.State("url", "user", "password", Set.of(), Set.of(), List.of(), null));
    }

    @Test
    void corruptedStoredConfigurationFailsExplicitlyInsteadOfSilentlyResettingAuthorization() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("invalid json"));
        assertThatIllegalStateException().isThrownBy(() -> new ZabbixSettings(jdbc, new ObjectMapper(), "", "", ""))
            .withMessage("Não foi possível ler a configuração Zabbix");
    }

    @Test
    void databaseFailureDoesNotPublishUnpersistedConfiguration() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        var settings = new ZabbixSettings(jdbc, new ObjectMapper(), "url", "user", "password");
        var original = settings.current();
        when(jdbc.update(eq("UPDATE zabbix_settings SET payload = ? WHERE id = 1"), any(Object.class)))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        var changed = new ZabbixSettings.State("new", "new", "new", Set.of(), Set.of(), List.of(), null);
        assertThatIllegalStateException().isThrownBy(() -> settings.save(changed));
        assertThat(settings.current()).isSameAs(original);
    }
}
