package br.com.scriptping.maps;

import br.com.scriptping.accesspoint.AccessPointRepository;
import br.com.scriptping.mikrotik.MikrotikService;
import br.com.scriptping.zabbix.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@JdbcTest
@Import(MappedConnectionController.class)
class MappedConnectionTests {
    @Autowired MappedConnectionController controller;
    @MockitoBean ZabbixService inventory;
    @MockitoBean MikrotikService mikrotik;
    @MockitoBean AccessPointRepository equipment;

    void equipment() {
        when(inventory.hosts()).thenReturn(List.of(new Host("a", "A", "10.0.0.1", List.of()), new Host("b", "B", "10.0.0.2", List.of())));
        when(equipment.configuration(anyString())).thenReturn(new AccessPointRepository.Configuration("MikroTik", "user", "encrypted", 8728, true, "ACTIVE", "", "10.0.0.1", false));
        when(mikrotik.query(any(), eq("interfaces"), isNull())).thenReturn(new MikrotikService.Snapshot(1, List.of(Map.of(".id", "*1", "name", "ether1", "type", "ether"), Map.of("name", "bridge", "type", "bridge")), ""));
    }
    @Test void savesIndependentEntityAndRejectsVirtualOrMissingInterfaces() {
        equipment();
        var saved = controller.save(new MappedConnectionController.Connection(null, "Cabo A-B", "a", "ether1", "b", "ether1"));
        assertThat(saved.id()).isNotBlank();
        assertThat(controller.all()).containsExactly(saved);
        assertThatThrownBy(() -> controller.save(new MappedConnectionController.Connection(null, "Inválida", "a", "bridge", "b", "ether1"))).isInstanceOf(ResponseStatusException.class);
        assertThat(controller.all()).hasSize(1);
    }
    @Test void cachesReadingsAndSeparatesCollectionErrorFromDown() {
        equipment();
        var c = controller.save(new MappedConnectionController.Connection(null, "Rede", "a", "ether1", "b", "ether1"));
        when(mikrotik.query(any(), eq("connection"), eq("ether1"))).thenAnswer(call -> {
            Host host = call.getArgument(0);
            if (host.id().equals("b")) throw new IllegalStateException("API indisponível");
            return new MikrotikService.Snapshot(System.currentTimeMillis(), List.of(Map.of("running", "true", "rx-bits-per-second", "1000", "tx-bits-per-second", "2000")), "");
        });
        var first = controller.readings(Set.of(c.id())).getFirst();
        assertThat(first.a().up()).isTrue();
        assertThat(first.a().rxBps()).isEqualTo("1000");
        assertThat(first.b().error()).isNotNull();
        assertThat(controller.readings(Set.of(c.id()))).containsExactly(first);
        verify(mikrotik, times(2)).query(any(), eq("connection"), eq("ether1"));
        assertThat(controller.readings(Set.of("missing"))).isEmpty();
    }
}
