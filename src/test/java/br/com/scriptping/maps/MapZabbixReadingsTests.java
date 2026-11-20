package br.com.scriptping.maps;

import br.com.scriptping.zabbix.ZabbixClient;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MapZabbixReadingsTests {
    final ZabbixClient zabbix = mock(ZabbixClient.class);
    final AtomicLong clock = new AtomicLong(1_000_000);
    final MapZabbixReadings cache = new MapZabbixReadings(zabbix, clock::get);
    final MapZabbixReadings.Key key = new MapZabbixReadings.Key("1", "ether1");
    final ObjectMapper json = new ObjectMapper();
    @AfterEach void close() { cache.close(); }
    JsonNode node(String text) throws Exception { return json.readTree(text); }
    List<JsonNode> catalogue() throws Exception {
        var result = new ArrayList<JsonNode>();
        String[] metric = {"status", "in", "out", "speed"};
        String[] oid = {"ifOperStatus", "ifHCInOctets", "ifHCOutOctets", "ifHighSpeed"};
        for (int i = 0; i < metric.length; i++) result.add(node("""
            {"itemid":"%s","hostid":"1","key_":"net.if.%s[%s.7]","name":"Interface ether1(Uplink): metric",
             "units":"%s","delay":"60s"}
            """.formatted(i + 1, metric[i], oid[i], i == 0 ? "" : "bps")));
        return result;
    }
    List<JsonNode> values() throws Exception {
        var result = new ArrayList<JsonNode>();
        String[] value = {"1", "0", "1234567", "10000000000"};
        for (int i = 0; i < value.length; i++) result.add(node("""
            {"itemid":"%s","lastvalue":"%s","lastclock":"1000","status":"0","state":"0"}
            """.formatted(i + 1, value[i])));
        return result;
    }
    void ready() throws Exception {
        when(zabbix.mapInterfaceItems(anySet())).thenReturn(catalogue());
        when(zabbix.mapItemValues(anySet())).thenReturn(values());
    }
    @Test void batchesItemsUsesZabbixRatesAndSharesCacheAcrossConsumers() throws Exception {
        ready();
        var first = cache.read(Set.of(key), false).get(key);
        assertThat(first.up()).isTrue();
        assertThat(first.rxBps()).isEqualTo("0");
        assertThat(first.txBps()).isEqualTo("1234567");
        assertThat(first.capacityBps()).isEqualTo(10_000_000_000L);
        assertThat(first.measuredAt()).isEqualTo(1_000_000);
        assertThat(first.validUntil()).isEqualTo(1_180_000);
        clock.addAndGet(60_000);
        assertThat(cache.read(Set.of(key), false).get(key)).isEqualTo(first);
        verify(zabbix).mapInterfaceItems(Set.of("1"));
        verify(zabbix).mapItemValues(Set.of("1", "2", "3", "4"));
        cache.read(Set.of(key), true);
        verify(zabbix, times(2)).mapItemValues(anySet());
        verify(zabbix, times(1)).mapInterfaceItems(anySet());
    }
    @Test void joinsConcurrentFirstAndManualLoadsWithoutDuplicateQueries() throws Exception {
        ready();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var data = values();
        when(zabbix.mapItemValues(anySet())).thenAnswer(call -> {
            entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return data;
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> cache.read(Set.of(key), false));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> cache.read(Set.of(key), true));
            // Hold the shared flight until the second reader has joined it.
            Thread.sleep(50);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
        }
        verify(zabbix).mapItemValues(anySet());
    }
    @Test void expiresAfterFiveMinutesAndDoesNotRenewInterestDuringRefresh() throws Exception {
        ready(); cache.read(Set.of(key), false);
        clock.addAndGet(300_000); cache.tick();
        verify(zabbix).mapItemValues(anySet());
        cache.read(Set.of(key), false);
        verify(zabbix, times(2)).mapInterfaceItems(anySet());
        verify(zabbix, times(2)).mapItemValues(anySet());
    }
    @Test void refreshesActiveInterestsInBackgroundAndRetainsOldSamplesOnFailure() throws Exception {
        ready(); var first = cache.read(Set.of(key), false).get(key);
        var entered = new CountDownLatch(1);
        when(zabbix.mapItemValues(anySet())).thenAnswer(call -> { entered.countDown(); throw new IllegalStateException(); });
        clock.addAndGet(60_000); cache.tick();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var result = cache.read(Set.of(key), true).get(key);
        assertThat(result.txBps()).isEqualTo(first.txBps());
        assertThat(result.measuredAt()).isEqualTo(first.measuredAt());
        assertThat(result.queriedAt()).isEqualTo(first.queriedAt());
        assertThat(result.queryError()).contains("indisponível");
    }
    @Test void reportsMissingAmbiguousDisabledAndUnsupportedItemsWithoutImplyingDown() throws Exception {
        ready();
        var missing = new MapZabbixReadings.Key("1", "ether10");
        assertThat(cache.read(Set.of(missing), false).get(missing).error()).contains("sem correspondência");
        var rows = catalogue(); var duplicate = new ArrayList<>(rows); duplicate.add(rows.getFirst());
        when(zabbix.mapInterfaceItems(anySet())).thenReturn(duplicate);
        clock.addAndGet(600_000); cache.tick();
        assertThat(cache.read(Set.of(key), false).get(key).error()).contains("ambígua");
    }
    @Test void reportsInitialZabbixFailureAndRecoversOnManualRefresh() throws Exception {
        when(zabbix.mapInterfaceItems(anySet())).thenThrow(new IllegalStateException());
        var failed = cache.read(Set.of(key), false).get(key);
        assertThat(failed.error()).contains("indisponível");
        assertThat(failed.queryError()).contains("indisponível");
        assertThat(failed.measuredAt()).isZero();
        reset(zabbix); ready();
        var recovered = cache.read(Set.of(key), true).get(key);
        assertThat(recovered.error()).isNull(); assertThat(recovered.queryError()).isNull();
        assertThat(recovered.txBps()).isEqualTo("1234567");
    }
    @Test void resolvesManufacturerIndependentNamesWithoutSubstringMatches() throws Exception {
        String[] names = {"ether1", "ten-gigabit-ethernet 1/1/18", "TenGigabitEthernet0/0/0", "Wireless"};
        for (String name : names) {
            var item = node("""
                {"itemid":"1","hostid":"1","key_":"net.if.status[ifOperStatus.7]",
                 "name":"Interface %s(Uplink): Operational status","delay":"1m"}
                """.formatted(name));
            when(zabbix.mapInterfaceItems(anySet())).thenReturn(List.of(item));
            when(zabbix.mapItemValues(anySet())).thenReturn(values());
            try (var reader = new MapZabbixReadings(zabbix, clock::get)) {
                var requested = new MapZabbixReadings.Key("1", name.replace(" 1/", "-1/"));
                assertThat(reader.read(Set.of(requested), false).get(requested).error()).as(name).isNull();
            }
        }
    }
    @Test void disabledOrUnsupportedStatusNeverBecomesAnOperationalDown() throws Exception {
        ready();
        var data = values();
        var status = (com.fasterxml.jackson.databind.node.ObjectNode) data.getFirst();
        when(zabbix.mapItemValues(anySet())).thenReturn(data);
        status.put("status", "1").put("lastvalue", "2");
        assertThat(cache.read(Set.of(key), false).get(key).error()).contains("desabilitado");
        status.put("status", "0").put("state", "1");
        assertThat(cache.read(Set.of(key), true).get(key).error()).contains("não suportado");
        status.put("state", "0").put("lastvalue", "4");
        assertThat(cache.read(Set.of(key), true).get(key).error()).contains("desconhecido");
        status.put("lastvalue", "2");
        var down = cache.read(Set.of(key), true).get(key);
        assertThat(down.error()).isNull(); assertThat(down.up()).isFalse();
    }
    @Test void expiresOnlyUnusedInterfacesAndKeepsOtherConsumersActive() throws Exception {
        ready();
        var unused = new MapZabbixReadings.Key("1", "ether10");
        cache.read(Set.of(key, unused), false);
        clock.addAndGet(240_000);
        cache.read(Set.of(key), false);
        clock.addAndGet(60_000); cache.tick();
        cache.read(Set.of(key), true);
        // Re-adding an expired interface requires a load; the retained one remains memory-only.
        int before = mockingDetails(zabbix).getInvocations().size();
        cache.read(Set.of(key), false);
        assertThat(mockingDetails(zabbix).getInvocations()).hasSize(before);
        cache.read(Set.of(unused), false);
        verify(zabbix, atLeast(3)).mapItemValues(anySet());
    }
    @Test void treatsEachTrafficClockSeparatelyAndRejectsCumulativeUnits() throws Exception {
        ready();
        var items = catalogue();
        ((com.fasterxml.jackson.databind.node.ObjectNode) items.get(1)).put("units", "B");
        ((com.fasterxml.jackson.databind.node.ObjectNode) items.get(2)).put("units", "Bps");
        var data = values();
        ((com.fasterxml.jackson.databind.node.ObjectNode) data.get(2)).put("lastclock", "500");
        when(zabbix.mapInterfaceItems(anySet())).thenReturn(items);
        when(zabbix.mapItemValues(anySet())).thenReturn(data);
        var result = cache.read(Set.of(key), false).get(key);
        assertThat(result.up()).isTrue();
        assertThat(result.rxBps()).isNull();
        assertThat(result.txBps()).isEqualTo("9876536");
        assertThat(result.txValidUntil()).isLessThan(clock.get());
        assertThat(result.trafficError()).isNotNull();
    }
}
