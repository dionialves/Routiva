package br.com.scriptping.zabbix;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ZabbixSettingsPersistenceTests {
    @Test
    void emptyDatabaseUsesEnvironmentDefaults() {
        var repository = mock(ZabbixSettingsRepository.class);
        when(repository.findById(1)).thenReturn(Optional.empty());
        var settings = new ZabbixSettings(repository, new ObjectMapper(), "url", "user", "password");
        assertThat(settings.current()).isEqualTo(new ZabbixSettings.State("url", "user", "password", Set.of(), Set.of(), List.of(), null));
    }

    @Test
    void corruptedStoredConfigurationFailsExplicitlyInsteadOfSilentlyResettingAuthorization() {
        var repository = mock(ZabbixSettingsRepository.class);
        when(repository.findById(1)).thenReturn(Optional.of(new ZabbixSettingsEntity("invalid json")));
        assertThatIllegalStateException().isThrownBy(() -> new ZabbixSettings(repository, new ObjectMapper(), "", "", ""))
            .withMessage("Não foi possível ler a configuração Zabbix");
    }

    @Test
    void databaseFailureDoesNotPublishUnpersistedConfiguration() {
        var repository = mock(ZabbixSettingsRepository.class);
        when(repository.findById(1)).thenReturn(Optional.empty());
        var settings = new ZabbixSettings(repository, new ObjectMapper(), "url", "user", "password");
        var original = settings.current();
        when(repository.saveAndFlush(any(ZabbixSettingsEntity.class)))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        var changed = new ZabbixSettings.State("new", "new", "new", Set.of(), Set.of(), List.of(), null);
        assertThatIllegalStateException().isThrownBy(() -> settings.save(changed));
        assertThat(settings.current()).isSameAs(original);
    }
}
