package br.com.scriptping.availability;

import br.com.scriptping.zabbix.Host;
import br.com.scriptping.zabbix.ZabbixService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvailabilitySnapshotCacheTests extends MinuteTestSupport {
    @Test void acknowledgedHostRemainsVisibleWithoutContributingMeasurements() {
        var acknowledged = new Host("ack", "Reconhecido", "127.0.0.2", host.groups(), true);
        insert(end-60000, 10d, null);
        insert(acknowledged, end-60000, null, "Tempo esgotado", 20);
        drain(10);
        var builder = mock(PacketLossAnalysisService.class);
        var monitor = mock(ZabbixService.class);
        var repository = new AvailabilitySnapshotRepository(jdbc, new ObjectMapper());
        var hosts = List.of(host, acknowledged);
        when(monitor.hosts()).thenReturn(hosts);
        when(builder.advance(anyList(), nullable(AvailabilitySnapshot.class)))
            .thenReturn(queries.buildAt(hosts, AvailabilityDashboard.MINUTES, now));
        var cache = new AvailabilitySnapshotCache(builder, repository, monitor);
        cache.refresh();
        cache.restore();
        var view = cache.query(1440, "").view();
        assertThat(view.hosts()).extracting(AvailabilityDashboard.Row::id).containsExactly(host.id(), "ack");
        assertThat(view.hosts().get(1).status()).isEqualTo("RECONHECIDO");
        assertThat(view.hosts().get(1).metrics().total()).isZero();
        assertThat(view.report().total().technical()).isEqualTo(100d);
        var group = cache.groupTotals(1440, hosts).groups().getFirst();
        assertThat(group.hosts()).isEqualTo(1);
        assertThat(group.metrics().technical()).isEqualTo(100d);
    }

    @Test void archivedHostContributesToIndicatorsButNotActiveRowsAfterRestore() {
        var archived = new Host("old", "Removido", "127.0.0.2", host.groups());
        insert(end-60000, 10d, null);
        insert(archived, end-60000, null, "Tempo esgotado", 20);
        drain(10);
        var builder = mock(PacketLossAnalysisService.class);
        var monitor = mock(ZabbixService.class);
        var repository = new AvailabilitySnapshotRepository(jdbc, new ObjectMapper());
        when(monitor.hosts()).thenReturn(List.of(host));
        when(monitor.historicalHosts()).thenReturn(List.of(host, archived));
        when(builder.advance(eq(List.of(host, archived)), nullable(AvailabilitySnapshot.class)))
            .thenReturn(queries.buildAt(List.of(host, archived), AvailabilityDashboard.MINUTES, now));
        var cache = new AvailabilitySnapshotCache(builder, repository, monitor);
        cache.refresh();
        var restored = new AvailabilitySnapshotCache(builder, repository, monitor);
        restored.restore();
        var view = restored.query(1440, "").view();
        assertThat(view.hosts()).extracting(AvailabilityDashboard.Row::id).containsExactly(host.id());
        assertThat(view.report().total().technical()).isEqualTo(50d);
        assertThat(restored.host(archived.id(), 1440).total().technical()).isZero();
        var group = restored.groupTotals(1440, List.of(host)).groups().getFirst();
        assertThat(group.hosts()).isEqualTo(1);
        assertThat(group.metrics().technical()).isEqualTo(50d);
        assertThat(restored.groupTotals(1440, List.of()).groups().getFirst().hosts()).isZero();
    }

    @Test void persistsRestoresEveryPeriodAndServesAllHttpSelectionsWithoutStorage() {
        var other=new Host("b", "Produção B", "127.0.0.2", List.of(new Host.Group("other", "Outro")));
        insert(end-60000, 10d, null);
        insert(other, end-60000, null, "Tempo esgotado", 20);
        drain(10);
        var builder=mock(PacketLossAnalysisService.class);
        var monitor=mock(ZabbixService.class);
        var repository=spy(new AvailabilitySnapshotRepository(jdbc, new ObjectMapper()));
        when(monitor.hosts()).thenReturn(List.of(host, other));
        when(builder.advance(anyList(),nullable(AvailabilitySnapshot.class))).thenReturn(queries.buildAt(List.of(host, other), AvailabilityDashboard.MINUTES, now));
        var cache=new AvailabilitySnapshotCache(builder, repository, monitor);
        assertThat(cache.query(5, "g").view()).isNull();
        cache.refresh();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM availability_snapshot", Integer.class)).isEqualTo(1);
        var restored=new AvailabilitySnapshotCache(builder, repository, monitor);
        restored.restore();
        clearInvocations(builder, repository, monitor);
        for (int minutes : AvailabilityDashboard.MINUTES) {
            var original=cache.query(minutes, "").view();
            assertThat(restored.query(minutes, "").view()).isEqualTo(original);
            assertThat(original.report().total().technical()).isEqualTo(50d);
            assertThat(restored.query(minutes, "g").view().report().total().technical()).isEqualTo(100d);
        }
        var controller=new AvailabilityDashboardController(new AvailabilityDashboard(restored));
        var response=controller.dashboard(1440, "", "producao", "technical", "desc", 99).getBody();
        assertThat(response.page()).isEqualTo(new AvailabilityDashboardController.Page(1,1,1,2,20));
        assertThat(response.view().hosts().getFirst().id()).isEqualTo("b");
        assertThat(response.view().report().total().technical()).isEqualTo(50d);
        assertThat(new HostAvailabilityService(restored).report(host.id(),24).total().technical()).isEqualTo(100d);
        assertThatThrownBy(() -> restored.query(5, "missing")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(builder, repository, monitor);
    }

    @Test void publicationWaitsForPersistenceAndFailuresKeepPreviousMemoryAndDatabaseVersion() {
        insert(end-60000, 10d, null); drain(10);
        var builder=mock(PacketLossAnalysisService.class);
        var monitor=mock(ZabbixService.class);
        var repository=spy(new AvailabilitySnapshotRepository(jdbc, new ObjectMapper()));
        when(monitor.hosts()).thenReturn(List.of(host));
        when(builder.advance(anyList(),nullable(AvailabilitySnapshot.class))).thenReturn(queries.buildAt(List.of(host), AvailabilityDashboard.MINUTES, now));
        var cache=new AvailabilitySnapshotCache(builder, repository, monitor);
        cache.refresh();
        var previous=cache.query(5, "").view();
        insert(end-30000, null, "Tempo esgotado"); drain(10);
        var next=queries.buildAt(List.of(host), AvailabilityDashboard.MINUTES, now);
        when(builder.advance(anyList(),nullable(AvailabilitySnapshot.class))).thenReturn(next);
        doAnswer(call -> {
            assertThat(cache.query(5, "").view()).isSameAs(previous);
            throw new IllegalStateException("simulated database failure");
        }).when(repository).save(any(), any());
        cache.refresh();
        assertThat(cache.query(5, "").view()).isSameAs(previous);
        assertThat(repository.load().version()).isEqualTo(previous.report().version().split(":")[0]);
        doCallRealMethod().when(repository).save(any(), any());
        cache.refresh();
        assertThat(cache.query(5, "").view().report().total().technical()).isEqualTo(50d);
        assertThat(repository.load().version()).isEqualTo(next.version());
        when(builder.advance(anyList(),nullable(AvailabilitySnapshot.class))).thenThrow(new IllegalStateException("simulated calculation failure"));
        cache.refresh();
        assertThat(cache.query(5, "").view().report().total().technical()).isEqualTo(50d);
    }

    @Test void slowPublisherDoesNotBlockAggregationOrCacheReaders() throws Exception {
        insert(end-60000,10d,null); drain(10);
        var builder=mock(PacketLossAnalysisService.class);
        var monitor=mock(ZabbixService.class);
        var repository=new AvailabilitySnapshotRepository(jdbc,new ObjectMapper());
        when(monitor.hosts()).thenReturn(List.of(host));
        var initial=queries.buildAt(List.of(host),AvailabilityDashboard.MINUTES,now);
        when(builder.advance(anyList(),nullable(AvailabilitySnapshot.class))).thenReturn(initial);
        var cache=new AvailabilitySnapshotCache(builder,repository,monitor);
        cache.refresh();
        var previous=cache.query(5, "").view();
        var started=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(builder.advance(anyList(),nullable(AvailabilitySnapshot.class))).thenAnswer(call -> {
            started.countDown();
            if (!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return initial;
        });
        try (var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var publishing=pool.submit(cache::refresh);
            try {
                assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                insert(end-30000,null,"Tempo esgotado");
                pool.submit(() -> new br.com.scriptping.aggregation.MinuteAggregationScheduled(summaries,100).aggregate())
                    .get(5,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(count("lost")).isEqualTo(1);
                assertThat(cache.query(5, "").view()).isSameAs(previous);
            } finally { release.countDown(); }
            publishing.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test void acknowledgementPreservesHistoricalPeriodsButExcludesHostFromIndicators() {
        var acknowledged=new Host(host.id(),host.name(),host.ip(),host.groups(),true);
        for (int i=0;i<200;i++) insert(end-3600000, 10d, null);
        insert(end-60000, null, "Tempo esgotado"); drain(1000);
        var snapshot=queries.buildAt(List.of(acknowledged), AvailabilityDashboard.MINUTES, now);
        assertThat(snapshot.view(5, List.of(acknowledged)).hosts().getFirst().status()).isEqualTo("RECONHECIDO");
        assertThat(snapshot.view(1440, List.of(acknowledged)).report().total().total()).isZero();
        assertThat(snapshot.periods().get(5).hosts().get(host.id()).status()).isEqualTo("CRITICO");
        assertThat(snapshot.periods().get(1440).hosts().get(host.id()).status()).isEqualTo("DEGRADADO");
        assertThat(snapshot.periods().get(1440).hosts().get(host.id()).total().metrics().loss()).isCloseTo(100d/201, within(0.00001));
    }

    @Test void coverageIncludesMissingHostsWithoutConvertingGapsToDowntimeAndTargetChangesAreIsolated() {
        var missing=new Host("b", "Missing", "127.0.0.2", List.of());
        var previousIp=new Host(host.id(),host.name(),"127.0.0.3",host.groups());
        insert(previousIp,end-60000,null,"Tempo esgotado",20);
        insert(end-60000,10d,null); drain(10);
        var snapshot=queries.buildAt(List.of(host,missing), List.of(5), now);
        var view=snapshot.view(5,List.of(host,missing));
        assertThat(view.report().total().technical()).isEqualTo(100d);
        assertThat(view.report().observedMillis()).isEqualTo(30000);
        assertThat(view.hosts().get(1).metrics().technical()).isNull();
        assertThat(view.report().series().stream().filter(p -> p.metrics().technical()!=null)).hasSize(1);
    }

    @Test void corruptSavedGenerationLeavesLoadingUntilBackgroundRebuild() {
        jdbc.update("INSERT INTO availability_snapshot VALUES(1,1,0,'invalid')");
        var repository=new AvailabilitySnapshotRepository(jdbc,new ObjectMapper());
        var builder=mock(PacketLossAnalysisService.class);
        var monitor=mock(ZabbixService.class);
        var cache=new AvailabilitySnapshotCache(builder,repository,monitor);
        cache.restore();
        assertThat(cache.query(1440, "").view()).isNull();
        insert(end-60000, 10d, null); drain(10);
        when(monitor.hosts()).thenReturn(List.of(host));
        when(builder.advance(anyList(),nullable(AvailabilitySnapshot.class))).thenReturn(queries.buildAt(List.of(host),AvailabilityDashboard.MINUTES,now));
        cache.refresh();
        assertThat(cache.query(1440, "").view().report().total().technical()).isEqualTo(100d);
    }
}
