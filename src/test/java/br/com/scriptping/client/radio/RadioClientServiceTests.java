package br.com.scriptping.client.radio;

import br.com.scriptping.ixc.IxcClient;
import br.com.scriptping.zabbix.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RadioClientServiceTests {
    static final String MAC = "AA:BB:CC:DD:EE:FF", OTHER = "11:22:33:44:55:66";
    JdbcTemplate db;
    IxcClient ixc;
    ZabbixService inventory;
    RadioClientService service;
    ObjectMapper mapper = new ObjectMapper();
    long now;
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql")); schema.execute(source); schema.execute(source);
        db = new JdbcTemplate(source); ixc = mock(IxcClient.class); inventory = mock(ZabbixService.class);
        when(ixc.findByMacs(anyCollection())).thenReturn(List.of());
        when(inventory.hosts()).thenReturn(List.of(new Host("ap1", "AP 1", "192.0.2.1", List.of()), new Host("ap2", "AP 2", "192.0.2.2", List.of())));
        service = new RadioClientService(db, new TransactionTemplate(new DataSourceTransactionManager(source)), mapper, inventory, 2, 10000);
        now = System.currentTimeMillis() - 1000;
    }
    IxcClient.Login login(String name, String mac) throws Exception {
        var constructor = IxcClient.Login.class.getDeclaredConstructor(com.fasterxml.jackson.databind.JsonNode.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(mapper.readTree("{\"login\":\"" + name + "\",\"ip\":\"192.0.2.10\"}"), mac);
    }
    RadioCollection.Sample sample(String mac, long at, String signal, String... names) {
        return new RadioCollection.Sample(at, mac, Map.of("mac-address", mac, "signal", signal,
            "avg-signal", "-999", "tx-ccq", "80", "p-throughput", "12000", "tx-rate", "1Gbps", "rx-rate", "500kbps"), List.of(names));
    }
    RadioCollection.Batch batch(String host, List<RadioCollection.Sample> samples, RadioCollection.Latency... tests) {
        return new RadioCollection.Batch(UUID.randomUUID().toString(), host, host, "owner", now - 60000, now, samples, List.of(tests));
    }
    RadioCollection.Batch complete(String host, String mac, long at, String... names) {
        var batch = batch(host, List.of(sample(mac, at, "-60", names), sample(mac, at + 10000, "-80", names)));
        persist(batch); return batch;
    }
    void persist(RadioCollection.Batch batch) {
        List<IxcClient.Login> resolved;
        try { resolved = ixc.findByMacs(batch.samples().stream().map(RadioCollection.Sample::mac).toList()); }
        catch (ResponseStatusException unavailable) { resolved = List.of(); }
        service.persistCollection(batch, resolved);
    }
    @SuppressWarnings("unchecked") Map<String, Object> data(Map<String, Object> collection) { return (Map<String, Object>) collection.get("data"); }
    @Test void filtersUseCurrentGroupsOfTheApDisplayedForTheLatestValidCollection() {
        when(inventory.hosts()).thenReturn(List.of(
            new Host("ap1", "AP 1", "192.0.2.1", List.of(new Host.Group("g1", "Norte"))),
            new Host("ap2", "AP 2", "192.0.2.2", List.of(new Host.Group("g2", "Sul")))));
        complete("ap1", MAC, now - 40000, "alice");
        // An insufficient later observation on another AP must not change the group of the displayed collection.
        persist(batch("ap2", List.of(sample(MAC, now - 5000, "-60", "alice"))));
        assertThat(service.list().getFirst().get("groups")).isEqualTo(List.of(new Host.Group("g1", "Norte")));
        complete("ap2", MAC, now - 20000, "alice");
        assertThat(service.list().getFirst().get("groups")).isEqualTo(List.of(new Host.Group("g2", "Sul")));
    }
    @Test void loginSurvivesApMacAndIpChangesAndPreservesRawSamples() throws Exception {
        complete("ap1", MAC, now - 40000, "alice"); complete("ap2", OTHER, now - 20000, "alice");
        assertThat(service.list()).hasSize(1);
        var history = service.history(service.list().getFirst().get("id").toString(), 0);
        assertThat(history).hasSize(2); assertThat(history.getFirst().get("mac")).isEqualTo(OTHER);
        assertThat(data(history.getFirst()).get("observedLogin")).isEqualTo("alice");
        assertThat(mapper.writeValueAsString(data(history.getFirst()).get("metrics"))).contains("-70.0", "1000.0", "0.5", "12.0");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM radio_observation WHERE raw_data LIKE '%-999%'", Integer.class)).isEqualTo(4);
    }
    @Test void deferredIxcIdentificationMergesProvisionalHistoryWithoutInventingObservedLogin() throws Exception {
        complete("ap1", MAC, now - 50000); complete("ap1", MAC, now - 35000);
        assertThat(service.list()).hasSize(1);
        complete("ap2", OTHER, now - 20000, "alice");
        when(ixc.findByMacs(anyCollection())).thenReturn(List.of(login("alice", MAC)));
        complete("ap1", MAC, now - 10000);
        assertThat(service.list()).hasSize(1);
        var history = service.history(service.list().getFirst().get("id").toString(), 0);
        assertThat(history).hasSize(4); assertThat(data(history.getLast()).get("observedLogin")).isNull();
        assertThat(data(history.getFirst()).get("observedLogin")).isNull();
    }
    @Test void transferAndAmbiguousObservedIdentitiesKeepSeparatePeriods() {
        complete("ap1", MAC, now - 50000, "alice"); complete("ap2", MAC, now - 30000, "bob");
        complete("ap2", MAC, now - 10000, "alice", "bob");
        assertThat(service.list()).hasSize(3);
        for (var client : service.list()) assertThat(service.history(client.get("id").toString(), 0)).hasSize(1);
        complete("ap2", MAC, now - 10000, "bob"); assertThat(service.list()).hasSize(3);
    }
    @Test void failedIxcPreservesManualIdentityAndPreviewIsReadOnly() {
        complete("ap1", MAC, now - 40000); String source = service.list().getFirst().get("id").toString();
        assertThat(service.manual(source, "alice", now - 60000, "operator", false).get("collections")).isEqualTo(1L);
        assertThat(service.list().getFirst().get("login")).isNull();
        service.manual(source, "alice", now - 60000, "operator", true);
        when(ixc.findByMacs(anyCollection())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY));
        complete("ap1", MAC, now - 20000);
        assertThat(service.list()).hasSize(1); assertThat(service.history(source, 0)).hasSize(2);
        assertThat(db.queryForObject("SELECT operator_name FROM radio_mac_binding WHERE valid_to IS NULL", String.class)).isEqualTo("operator");
    }
    @Test void batchesAreIdempotentAndShortVisitsDoNotReplaceEligibleHistory() {
        var first = complete("ap1", MAC, now - 40000); persist(first);
        var second = complete("ap1", MAC, now - 20000);
        persist(batch("ap1", List.of(sample(MAC, now, "-20"))));
        var client = service.list().getFirst(); assertThat(service.history(client.get("id").toString(), 0)).hasSize(2);
        assertThat(((Map<?, ?>) client.get("latest")).get("visit_id")).isEqualTo(second.id());
    }
    @Test void simultaneousFinalizersPersistExactlyOneCollection() throws Exception {
        var batch = batch("ap1", List.of(sample(MAC, now - 20000, "-60"), sample(MAC, now - 10000, "-70")));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> persist(batch)); var b = executor.submit(() -> persist(batch)); a.get(); b.get();
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM radio_collection", Integer.class)).isEqualTo(1);
    }
    @Test void olderBatchProcessedLaterDoesNotReplaceNewerObservation() {
        var newer = complete("ap2", MAC, now - 20000); complete("ap1", MAC, now - 40000);
        assertThat(((Map<?, ?>) service.list().getFirst().get("latest")).get("visit_id")).isEqualTo(newer.id());
    }
    @Test void permissionsFilterLatestHistoryAndManualLink() {
        complete("ap1", MAC, now - 40000); complete("ap2", MAC, now - 20000);
        String client = service.list().getFirst().get("id").toString();
        when(inventory.hosts()).thenReturn(List.of(new Host("ap1", "AP 1", "192.0.2.1", List.of())));
        assertThat(service.history(client, 0)).hasSize(1);
        assertThat(((Map<?, ?>) service.list().getFirst().get("latest")).get("host_id")).isEqualTo("ap1");
        assertThatThrownBy(() -> service.manual(client, "alice", now - 60000, "operator", true)).isInstanceOf(ResponseStatusException.class);
        when(inventory.hosts()).thenReturn(List.of()); assertThat(service.list()).isEmpty();
    }
    @Test void manualMergeAndPartialPeriodKeepOriginalObservations() {
        complete("ap2", OTHER, now - 50000, "alice");
        persist(batch("ap1", List.of(sample(MAC, now - 40000, "-60"), sample(MAC, now - 30000, "-60"), sample(MAC, now - 20000, "-60"), sample(MAC, now - 10000, "-60"))));
        String source = service.list().stream().filter(c -> c.get("login") == null).findFirst().orElseThrow().get("id").toString();
        service.manual(source, "alice", now - 25000, "operator", true);
        assertThat(service.list()).hasSize(2);
        assertThat(service.history(source, 0).getFirst().get("observed_to")).isEqualTo(now - 30000);
        String target = service.list().stream().filter(c -> "alice".equals(c.get("login"))).findFirst().orElseThrow().get("id").toString();
        assertThat(service.history(target, 0)).hasSize(2); assertThat(data(service.history(target, 0).getFirst()).get("observedLogin")).isNull();
    }
    @Test void latencyIsScopedToVisitAndLateCompletionUpdatesAnAlreadyPersistedBatch() {
        var visit = complete("ap1", MAC, now - 20000);
        var stats = new LinkedHashMap<String, Object>(); stats.put("latency", null); stats.put("loss", 100d); stats.put("startedAt", now - 15000);
        persist(new RadioCollection.Batch(visit.id(), visit.host(), visit.hostName(), visit.owner(), visit.startedAt(), visit.touchedAt(), visit.samples(), List.of(new RadioCollection.Latency(MAC, now, stats))));
        var client = service.list().getFirst().get("id").toString(); assertThat(service.history(client, 0).getFirst()).containsKey("latency");
        complete("ap1", MAC, now - 10000); assertThat(service.history(client, 0).getFirst()).doesNotContainKey("latency");
    }
    @Test void ownersSharingOneMacInTheSameVisitKeepTheCorrectTestPeriod() {
        var samples = List.of(sample(MAC, now - 40000, "-60", "alice"), sample(MAC, now - 30000, "-60", "alice"),
            sample(MAC, now - 20000, "-60", "bob"), sample(MAC, now - 10000, "-60", "bob"));
        persist(batch("ap1", samples,
            new RadioCollection.Latency(MAC, now - 25000, Map.of("startedAt", now - 35000, "latency", 2d, "loss", 0d)),
            new RadioCollection.Latency(MAC, now, Map.of("startedAt", now - 15000, "latency", 4d, "loss", 50d))));
        assertThat(service.list()).hasSize(2);
        for (var client : service.list()) {
            var test = (Map<?, ?>) service.history(client.get("id").toString(), 0).getFirst().get("latency");
            assertThat(((Map<?, ?>) test.get("data")).get("loss")).isEqualTo(client.get("login").equals("alice") ? 0d : 50d);
        }
    }

    @Test void deferredUniqueLoginTransfersOnlyTheNewVisitAndAmbiguityRemainsPending() throws Exception {
        complete("ap1", MAC, now - 50000, "alice");
        when(ixc.findByMacs(anyCollection())).thenReturn(List.of(login("bob", MAC)));
        complete("ap2", MAC, now - 30000);
        assertThat(service.list()).hasSize(2);
        when(ixc.findByMacs(anyCollection())).thenReturn(List.of(login("alice", MAC), login("bob", MAC)));
        complete("ap2", MAC, now - 10000);
        assertThat(service.list()).hasSize(3);
        for (var client : service.list()) assertThat(service.history(client.get("id").toString(), 0)).hasSize(1);
    }
    @Test void previousVersionUnfinishedVisitsAreRecoveredInTheWorker() {
        complete("ap1", MAC, now - 40000);
        db.update("DELETE FROM radio_collection"); db.update("UPDATE radio_visit SET closed = FALSE");
        service.recoverUnfinishedVisits(); service.recoverUnfinishedVisits();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM radio_collection", Integer.class)).isEqualTo(1);
    }

    @Test void persistsTheApDiagnosisEvenWhenSignalAloneLooksNormal() throws Exception {
        var raw = new LinkedHashMap<>(sample(MAC, now - 20000, "-40").raw());
        raw.put("signal-severity", "0"); raw.put("quality-severity", "3"); raw.put("quality", "Crítico");
        raw.put("quality-primary-reason", "TX CCQ reduzido sob tráfego significativo.");
        raw.put("quality-summary", "Degradação identificada sob tráfego.");
        persist(batch("ap1", List.of(
            new RadioCollection.Sample(now - 20000, MAC, raw, List.of()),
            new RadioCollection.Sample(now - 10000, MAC, raw, List.of()))));
        var stored = mapper.readTree(db.queryForObject("SELECT data FROM radio_collection", String.class));
        assertThat(stored.path("connectionSeverity").asInt()).isEqualTo(3);
        assertThat(stored.path("signalSeverity").asInt()).isZero();
        assertThat(stored.path("connectionDiagnosis").path("label").asText()).isEqualTo("Crítico");
        assertThat(stored.path("connectionDiagnosis").path("reason").asText()).contains("CCQ");
        assertThat(stored.path("connectionDiagnosis").path("at").asLong()).isEqualTo(now - 10000);
    }

}
