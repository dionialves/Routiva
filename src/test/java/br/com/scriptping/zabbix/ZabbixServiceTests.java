package br.com.scriptping.zabbix;

import br.com.scriptping.ingestion.CollectorClient;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ZabbixServiceTests {
    private final ZabbixClient client = mock(ZabbixClient.class);
    private final CollectorClient collector = mock(CollectorClient.class);
    private final ZabbixSettings settings = mock(ZabbixSettings.class);
    private final Host.Group group = new Host.Group("g", "Rede");
    private final Host host = new Host("h", "Router", "10.0.0.1", List.of(group));
    private ZabbixSettings.State state;
    private ZabbixService monitor;

    @BeforeEach
    void setUp() {
        state = new ZabbixSettings.State("https://zabbix.test/api", "api", "secret",
            Set.of("g"), Set.of("h"), List.of(host), 123L);
        when(settings.current()).thenAnswer(call -> state);
        doAnswer(call -> { state = call.getArgument(0); return null; }).when(settings).save(any());
        monitor = new ZabbixService(client, collector, settings);
        clearInvocations(client, collector, settings);
    }

    @Test
    void startupRestoresConnectionAndInventoryWithoutFetchingRemoteData() {
        new ZabbixService(client, collector, settings);
        verify(client).configure(state.url(), state.username(), state.password());
        verify(collector).synchronize(List.of(host));
        verify(client, never()).fetchSelected(anySet(), anySet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not a url", "ftp://zabbix.test", "https://user:secret@zabbix.test", "https://zabbix.test/#secret"})
    void invalidConnectionDoesNotChangeSavedStateOrCollector(String url) {
        var before = state;
        assertThatIllegalArgumentException().isThrownBy(() -> monitor.connection(url, "api", "secret"));
        assertThat(state).isSameAs(before);
        verify(settings, never()).save(any());
        verifyNoInteractions(client, collector);
    }

    @Test
    void unchangedUrlKeepsPasswordAndInventoryWhenPasswordIsOmitted() {
        monitor.connection("  " + state.url() + "  ", " new-user ", "");
        assertThat(state.password()).isEqualTo("secret");
        assertThat(state.username()).isEqualTo("new-user");
        assertThat(state.inventory()).containsExactly(host);
        assertThat(state.lastSync()).isEqualTo(123L);
        verify(collector).synchronize(List.of(host));
    }

    @Test
    void changedServerRequiresNewPasswordAndClearsPreviousAuthorization() {
        assertThatIllegalArgumentException().isThrownBy(() -> monitor.connection("https://other.test", "api", ""));
        verify(settings, never()).save(any());
        monitor.connection("https://other.test", "api", "new-secret");
        assertThat(state.groups()).isEmpty();
        assertThat(state.hosts()).isEmpty();
        assertThat(state.inventory()).isEmpty();
        assertThat(state.lastSync()).isNull();
        verify(collector).synchronize(List.of());
    }

    @Test
    void storageFailureDoesNotReconfigureRunningClients() {
        doThrow(new IllegalStateException("storage unavailable")).when(settings).save(any());
        assertThatIllegalStateException().isThrownBy(() -> monitor.connection(state.url(), "api", "new-secret"));
        assertThat(state.password()).isEqualTo("secret");
        verifyNoInteractions(client, collector);
    }

    @Test
    void discoveryFailurePreservesSelectionAndRunningInventory() {
        when(client.groups()).thenReturn(List.of(group));
        when(client.discover(Set.of("g"))).thenThrow(new IllegalStateException("offline"));
        assertThatIllegalStateException().isThrownBy(() -> monitor.selectInventory(Set.of("g"), Set.of("h")));
        verify(settings, never()).save(any());
        verifyNoInteractions(collector);
        assertThat(monitor.hosts()).containsExactly(host);
    }

    @Test
    void failedSyncPreservesTimestampAndCanRecoverOnNextAttempt() {
        when(client.configured()).thenReturn(true);
        when(client.fetchSelected(Set.of("g"), Set.of("h")))
            .thenThrow(new IllegalStateException("offline")).thenReturn(List.of());
        assertThatIllegalStateException().isThrownBy(monitor::sync);
        assertThat(monitor.hosts()).containsExactly(host);
        assertThat(monitor.lastSync()).isEqualTo(123L);
        assertThat(monitor.status()).contains("Falha");
        verifyNoInteractions(collector);
        monitor.sync();
        assertThat(monitor.hosts()).isEmpty();
        assertThat(monitor.lastSync()).isGreaterThan(123L);
        assertThat(monitor.status()).contains("concluída");
        verify(collector).synchronize(List.of());
    }

    @Test
    void unconfiguredSyncCannotFetchOrPersistAnything() {
        assertThatIllegalArgumentException().isThrownBy(monitor::sync);
        verify(client, never()).fetchSelected(anySet(), anySet());
        verify(settings, never()).save(any());
        verifyNoInteractions(collector);
    }

    @Test
    void authorizationRequiresBothHostAndGroupAndRemovesUnselectedGroupMetadata() {
        var other = new Host.Group("other", "Other");
        var shared = new Host("h", "Router", "10.0.0.1", List.of(group, other), true);
        var denied = new Host("denied", "Denied", "10.0.0.2", List.of(group));
        var selected = ZabbixService.authorized(List.of(shared, denied), Set.of("g"), Set.of("h"));
        assertThat(selected).hasSize(1);
        assertThat(selected.getFirst().groups()).containsExactly(group);
        assertThat(selected.getFirst().acknowledgedIcmpProblem()).isTrue();
        assertThat(ZabbixService.authorized(List.of(shared), Set.of(), Set.of("h"))).isEmpty();
    }
}
