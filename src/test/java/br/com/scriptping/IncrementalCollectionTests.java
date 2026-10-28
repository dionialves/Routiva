package br.com.scriptping;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class IncrementalCollectionTests {
    final long now = 200_000_000L;
    final Host host = new Host("h", "Host", "10.0.0.1", List.of());
    JdbcTemplate jdbc;
    SampleStore store;
    PacketLossAnalysisService service;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "");
        ds.setUrl(ds.getUrl() + ";DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        store = spy(new SampleStore(jdbc, 48, 600));
        service = new PacketLossAnalysisService(store, new PacketLossPolicy(), 1000, 2000);
        service.configureIncremental(2, 1000);
    }
    @AfterEach void close() { service.closeCheckpointWriter(); }
    void add(long id, long at) {
        jdbc.update("INSERT INTO ping_sample(id,host_id,target,measured_at,latency,latency_limit) VALUES(?,?,?,?,?,?)",
            id, host.id(), host.ip(), at, 10d, 20d);
    }
    @Test void coldStartSkipsExistingRowsThenAggregatesOnlyNewIds() {
        add(1, now - 1000); add(2, now + 5000);
        service.refreshAt(now);
        verify(store, never()).readIncrement(anyLong(), anyLong(), anyLong(), any());
        assertThat(service.availability(host, 48).total().total()).isZero();
        assertThat(service.loadStatus().description()).isEqualTo("Indicadores atualizados");
        add(3, now - 2000); // late measurement, newly committed ID
        add(4, now + 5000); // new future sample
        add(5, now + 1);
        service.refreshAt(now);
        assertThat(service.loadStatus().phase()).isEqualTo("CATCHING_UP");
        IncrementalTestSupport.refreshFully(service, now + 10000);
        assertThat(service.availability(host, 48).total().total()).isEqualTo(3);
        assertThat(service.captureCheckpoint().parts()).noneMatch(p -> p.kind().equals("bootstrap"));
    }
    @Test void failedPageRetriesWithoutSkippingOrDuplicatingNewSamples() {
        service.refreshAt(now);
        add(1, now - 1); add(2, now - 2);
        doThrow(new IllegalStateException("offline")).when(store).readIncrement(anyLong(),anyLong(),anyLong(),any());
        assertThatThrownBy(() -> service.refreshAt(now)).isInstanceOf(IllegalStateException.class);
        assertThat(service.loadStatus().liveCursor()).isZero();
        doCallRealMethod().when(store).readIncrement(anyLong(),anyLong(),anyLong(),any());
        service.refreshAt(now);
        service.refreshAt(now);
        assertThat(service.availability(host, 48).total().total()).isEqualTo(2);
    }
    @Test void boundaryRewindStartsFreshWithoutReadingOldRows() {
        add(10, now - 1); service.refreshAt(now);
        add(11, now); service.refreshAt(now + 1);
        jdbc.update("DELETE FROM ping_sample");
        add(1, now);
        clearInvocations(store);
        service.refreshAt(now + 2);
        verify(store, never()).readIncrement(anyLong(),anyLong(),anyLong(),any());
        assertThat(service.availability(host, 48).total().total()).isZero();
        add(2, now + 3); service.refreshAt(now + 4);
        assertThat(service.availability(host, 48).total().total()).isEqualTo(1);
    }
}
