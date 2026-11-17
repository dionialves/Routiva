package br.com.scriptping.maps;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static br.com.scriptping.maps.NetworkMapModels.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@Import({NetworkMapService.class, NetworkMapPersistenceTests.Config.class})
class NetworkMapPersistenceTests {
    @TestConfiguration
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean br.com.scriptping.zabbix.ZabbixService inventory() {
            return org.mockito.Mockito.mock(br.com.scriptping.zabbix.ZabbixService.class);
        }
        @Bean br.com.scriptping.zabbix.ZabbixClient zabbix() {
            return org.mockito.Mockito.mock(br.com.scriptping.zabbix.ZabbixClient.class);
        }
    }

    @Autowired NetworkMapService service;
    @Autowired NetworkMapRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsAndUpdatesMapsRejectingStaleOrMissingRevisions() throws Exception {
        var layout = new Layout(null, "Rede", null, null, List.of(), List.of());
        var created = service.save(new Saved(null, 0, layout, null));
        assertThat(created.revision()).isEqualTo(1);
        assertThat(service.maps()).containsExactly(created);

        var updated = service.save(created);
        assertThat(updated.revision()).isEqualTo(2);
        assertThat(service.maps()).containsExactly(updated);
        assertThatThrownBy(() -> service.save(created))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
        assertThatThrownBy(() -> service.save(new Saved("missing", 1, layout, null)))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
        assertThat(repository.count()).isEqualTo(1);
        assertThat(service.maps()).containsExactly(updated);
    }

    @Test
    void persistsAcknowledgedIncidentVisibility() throws Exception {
        var layout = new Layout(null, "Rede", null, 0, false, List.of(), List.of());
        var saved = service.save(new Saved(null, 0, layout, null));
        assertThat(service.maps()).containsExactly(saved);
        assertThat(service.maps().getFirst().layout().showAcknowledged()).isFalse();
    }

    @Test
    void readsLegacyJsonAndOrdersTimestampsWithNullsLast() {
        String legacy = "{\"name\":\"Legado\",\"width\":800,\"height\":600,\"nodes\":[],\"links\":[]}";
        jdbc.update("INSERT INTO network_map VALUES (?, ?, ?, ?)", "legacy", 7, legacy, null);
        jdbc.update("INSERT INTO network_map VALUES (?, ?, ?, ?)", "b", 1, legacy, 200L);
        jdbc.update("INSERT INTO network_map VALUES (?, ?, ?, ?)", "a", 1, legacy, 200L);
        jdbc.update("INSERT INTO network_map VALUES (?, ?, ?, ?)", "older", 1, legacy, 100L);
        assertThat(service.maps()).extracting(Saved::id).containsExactly("a", "b", "older", "legacy");
        var saved = service.maps().getLast();
        assertThat(saved.revision()).isEqualTo(7);
        assertThat(saved.layout().publication()).isEqualTo(Publication.PUBLICADO);
        assertThat(saved.layout().minimumSeverity()).isZero();
        assertThat(saved.layout().showAcknowledged()).isTrue();
        assertThat(saved.updatedAt()).isNull();
    }

    @Test
    void persistsPortConnectionsAndRejectsOccupiedOrMissingPorts() throws Exception {
        var a = new Node("a", null, "host-a", null, "SW A", 0, 0, null, "switch",
            List.of(new Port("a1", "rj45"), new Port("a2", "qsfp")));
        var b = new Node("b", null, "host-b", null, "SW B", 300, 0, null, "switch",
            List.of(new Port("b1", "rj45"), new Port("b2", "qsfp")));
        var links = List.of(new Link("a", "b", "a1", "b1"), new Link("b", "a", "b2", "a2"));
        var saved = service.save(new Saved(null, 0,
            new Layout(null, "Portas", null, 0, List.of(a, b), links), null));
        assertThat(service.maps()).contains(saved);
        for (var invalid : List.of(
            List.of(new Link("a", "b", "missing", "b1")),
            List.of(new Link("a", "b", "a1", "b1"), new Link("a", "b", "a1", "b2")),
            List.of(new Link("a", "b", "a1", "b1"), new Link("b", "a", "b1", "a1")))) {
            assertThatThrownBy(() -> service.save(new Saved(null, 0,
                new Layout(null, "Inválido", null, 0, List.of(a, b), invalid), null)))
                .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    void persistsEditableRoutesAndRejectsInvalidGeometry() throws Exception {
        var a = new Node("a", null, null, null, "A", 0, 0, null);
        var b = new Node("b", null, null, null, "B", 200, 0, null);
        var bends = List.of(new Bend(0, 80), new Bend(200, 80), new Bend(200, -60));
        var link = new Link("a", "b", null, null, "corners", bends, "curved");
        var saved = service.save(new Saved(null, 0,
            new Layout(null, "Caminho", null, 0, List.of(a, b), List.of(link)), null));
        assertThat(service.maps()).contains(saved);
        assertThat(saved.layout().links().getFirst().points()).isEqualTo(bends);
        assertThat(saved.layout().links().getFirst().lineStyle()).isEqualTo("curved");
        for (var invalid : List.of(new Link("a", "b", null, null, "corners", List.of(), "invalid"),
                new Link("a", "b", null, null, "unknown", List.of()),
                new Link("a", "b", null, null, "corners", List.of(new Bend(Double.NaN, 0))))) {
            assertThatThrownBy(() -> service.save(new Saved(null, 0,
                new Layout(null, "Inválido", null, 0, List.of(a, b), List.of(invalid)), null)))
                .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    void persistsMultipleInterfaceSlotsAndRejectsInconsistentPorts() throws Exception {
        var slots = List.of(new Slot("slot1", "ether", "electrical", 1), new Slot("slot2", "1/1/", "sfp100g", 1));
        var ports = List.of(new Port("p1", "rj45", "slot1", "ether1", "electrical"),
            new Port("p2", "qsfp", "slot2", "1/1/1", "sfp100g"));
        var node = new Node("sw", null, null, null, "Switch", 0, 0, null, "switch", ports, slots);
        var saved = service.save(new Saved(null, 0, new Layout(null, "Slots", null, 0, List.of(node), List.of()), null));
        assertThat(service.maps()).contains(saved);
        assertThat(saved.layout().nodes().getFirst().slots()).isEqualTo(slots);
        var invalid = new Node("bad", null, null, null, "Switch", 0, 0, null, "switch", ports,
            List.of(new Slot("slot1", "ether", "electrical", 2)));
        assertThatThrownBy(() -> service.save(new Saved(null, 0,
            new Layout(null, "Inválido", null, 0, List.of(invalid), List.of()), null)))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void persistsInterfaceEnablementIndependentlyOfIconAndPreservesHiddenSlots() throws Exception {
        var slots = List.of(new Slot("s", "ether", "electrical", 1));
        var ports = List.of(new Port("p", "rj45", "s", "ether1", "electrical"));
        var circle = new Node("a", null, null, null, "Circle", 0, 0, null, "circle", ports, slots, true);
        var disabled = new Node("b", null, null, null, "Switch", 200, 0, null, "switch", ports, slots, false);
        var saved = service.save(new Saved(null, 0, new Layout(null, "Interfaces", null, 0,
            List.of(circle, disabled), List.of(new Link("a", "b", "p", "p"))), null));
        assertThat(service.maps()).contains(saved);
        var restored = service.maps().getFirst().layout().nodes();
        assertThat(restored.get(0).interfacesEnabled()).isTrue();
        assertThat(restored.get(1).interfacesEnabled()).isFalse();
        assertThat(restored.get(1).slots()).isEqualTo(slots);
        assertThat(restored.get(1).ports()).isEqualTo(ports);
    }

    @Test
    void persistsSwitchColorSeparatelyAndRejectsInvalidValues() throws Exception {
        var node = new Node("sw", null, null, "#123456", "Switch", 0, 0, null, "switch",
            List.of(), List.of(), false, "#2288cc");
        var saved = service.save(new Saved(null, 0, new Layout(null, "Cor", null, 0, List.of(node), List.of()), null));
        assertThat(service.maps()).contains(saved);
        assertThat(saved.layout().nodes().getFirst().switchColor()).isEqualTo("#2288cc");
        assertThat(saved.layout().nodes().getFirst().color()).isEqualTo("#123456");
        var invalid = new Node("bad", null, null, null, "Switch", 0, 0, null, "switch",
            List.of(), List.of(), false, "red");
        assertThatThrownBy(() -> service.save(new Saved(null, 0,
            new Layout(null, "Cor inválida", null, 0, List.of(invalid), List.of()), null)))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void legacyNodesAndLinksReceiveCompatibleDefaults() throws Exception {
        var mapper = new ObjectMapper();
        var node = mapper.readValue("{\"id\":\"old\",\"name\":\"Old\",\"x\":10,\"y\":20}", Node.class);
        assertThat(node.icon()).isEqualTo("circle");
        assertThat(node.ports()).isEmpty();
        assertThat(node.interfacesEnabled()).isFalse();
        var legacySwitch = mapper.readValue("{\"id\":\"sw\",\"name\":\"SW\",\"icon\":\"switch\",\"ports\":[{\"id\":\"p\",\"type\":\"sfp\"}]}", Node.class);
        assertThat(legacySwitch.interfacesEnabled()).isTrue();
        var link = mapper.readValue("{\"from\":\"a\",\"to\":\"b\"}", Link.class);
        assertThat(link.fromPort()).isNull();
        assertThat(link.toPort()).isNull();
        assertThat(link.style()).isEqualTo("straight");
        assertThat(link.lineStyle()).isEqualTo("straight");
        assertThat(link.points()).isEmpty();
    }

    @Test
    void rejectsInvalidTopologyBeforePersisting() {
        var layout = new Layout(null, "", null, null, List.of(), List.of());
        assertThatThrownBy(() -> service.save(new Saved(null, 0, layout, null)))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
        assertThat(repository.count()).isZero();
    }
}
