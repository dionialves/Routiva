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
    void reconciliationRepairsLegacyActiveInventoryAndPreservesHistoryOnRepeatedRuns() {
        when(client.configured()).thenReturn(true);
        when(client.fetchSelected(Set.of("g"), Set.of("h"))).thenReturn(List.of());
        monitor.reconcile();
        monitor.reconcile();
        assertThat(monitor.hosts()).isEmpty();
        assertThat(monitor.historicalHosts()).containsExactly(host);
        verify(collector, times(2)).synchronize(List.of());
    }

    @Test
    void failedReconciliationKeepsLegacyInventoryUntilSuccessfulRemoteConfirmation() {
        when(client.configured()).thenReturn(true);
        when(client.fetchSelected(anySet(), anySet())).thenThrow(new IllegalStateException("offline"));
        monitor.reconcile();
        assertThat(monitor.hosts()).containsExactly(host);
        verifyNoInteractions(collector);
    }

    @Test
    void unconfiguredSyncCannotFetchOrPersistAnything() {
        assertThatIllegalArgumentException().isThrownBy(monitor::sync);
        verify(client, never()).fetchSelected(anySet(), anySet());
        verify(settings, never()).save(any());
        verifyNoInteractions(collector);
    }

    @Test
    void refreshDoesNotSaveOrChangeCollector() {
        when(client.groups()).thenReturn(List.of(group));
        when(client.discover(Set.of("g"))).thenReturn(List.of(host));
        var result = monitor.inventory();
        assertThat(result.get("selectedHosts")).isEqualTo(Set.of("h"));
        verify(settings, never()).save(any());
        verifyNoInteractions(collector);
    }

    @Test
    void saveImportsImmediatelyDeduplicatesAndRepeatedRequestsAreIdempotent() {
        var added = new Host("new", "New", "10.0.0.2", List.of(group));
        when(client.groups()).thenReturn(List.of(group));
        when(client.discover(Set.of("g"))).thenReturn(List.of(host, added, added));
        when(client.fetchSelected(Set.of("g"), Set.of("h", "new"))).thenReturn(List.of(host, added, added));
        var first = monitor.saveAndImport(Set.of("h", "new"));
        assertThat(((java.util.Map<?, ?>) first.get("result")).get("imported")).isEqualTo(1);
        assertThat(state.inventory()).extracting(Host::id).containsExactlyInAnyOrder("h", "new");
        var second = monitor.saveAndImport(Set.of("h", "new"));
        assertThat(((java.util.Map<?, ?>) second.get("result")).get("imported")).isEqualTo(0);
        verify(collector, atLeastOnce()).synchronize(state.inventory());
    }

    @Test
    void partialImportDeactivatesMissingHostsAndReportsInvalidTargets() {
        var invalid = new Host("bad", "Bad", "", List.of(group));
        when(client.groups()).thenReturn(List.of(group));
        when(client.discover(Set.of("g"))).thenReturn(List.of(invalid));
        when(client.fetchSelected(Set.of("g"), Set.of("h", "bad"))).thenReturn(List.of(invalid));
        var result = monitor.saveAndImport(Set.of("h", "bad"));
        assertThat(state.hosts()).containsExactlyInAnyOrder("h", "bad");
        assertThat(state.inventory()).isEmpty();
        assertThat(monitor.historicalHosts()).containsExactly(host);
        assertThat(((java.util.Map<?, ?>) result.get("result")).get("state")).isEqualTo("partial");
        assertThat(state.imported()).containsExactly(host);
        @SuppressWarnings("unchecked")
        var entries = (List<ZabbixService.ImportEntry>) state.importResult().get("entries");
        assertThat(entries).hasSize(2);
        assertThat(entries).filteredOn(e -> e.id().equals("bad")).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("Bad");
            assertThat(e.ip()).isEmpty();
            assertThat(e.category()).isEqualTo("Erro de validação");
            assertThat(e.severity()).isEqualTo("error");
        });
        assertThat(entries).filteredOn(e -> e.id().equals("h")).singleElement()
            .satisfies(e -> assertThat(e.outcome()).isEqualTo("missing"));
    }

    @Test
    void importFailureIsPersistedSeparatelyFromSuccessfulSelectionSave() {
        when(client.groups()).thenReturn(List.of(group));
        when(client.discover(Set.of("g"))).thenReturn(List.of(host));
        when(client.fetchSelected(Set.of("g"), Set.of("h"))).thenThrow(new IllegalStateException("offline"));
        var response = monitor.saveAndImport(Set.of("h"));
        assertThat(state.hosts()).containsExactly("h");
        assertThat(state.inventory()).containsExactly(host);
        assertThat(state.importResult()).extractingByKey("state").isEqualTo("failed");
        assertThat(response.get("selectedHosts")).isEqualTo(Set.of("h"));
    }

    @Test
    void deselectionPreservesImportedRecordsAndStopsCollection() {
        when(client.groups()).thenReturn(List.of(group));
        when(client.discover(Set.of("g"))).thenReturn(List.of(host));
        monitor.saveAndImport(Set.of());
        assertThat(state.inventory()).isEmpty();
        assertThat(state.imported()).containsExactly(host);
        verify(collector, atLeastOnce()).synchronize(List.of());
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
