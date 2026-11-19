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

    private Saved mapWithSubMap(String name, Saved existing, String child) throws Exception {
        var node = new Node("group", "g1", null, null, "Grupo", 0, 0, null,
            "circle", List.of(), List.of(), false, null, child);
        return service.save(new Saved(existing == null ? null : existing.id(), existing == null ? 0 : existing.revision(),
            new Layout(name, null, 0, List.of(node), List.of()), null));
    }

    @Test
    void mappedGroupsRequireHostsThatBelongToEachEndpointGroup(
            @Autowired br.com.scriptping.zabbix.ZabbixService inventory) throws Exception {
        var hostA = new br.com.scriptping.zabbix.Host("ha", "Host A", "10.0.0.1",
            List.of(new br.com.scriptping.zabbix.Host.Group("ga", "Grupo A")));
        var hostB = new br.com.scriptping.zabbix.Host("hb", "Host B", "10.0.0.2",
            List.of(new br.com.scriptping.zabbix.Host.Group("gb", "Grupo B")));
        org.mockito.Mockito.when(inventory.hosts()).thenReturn(List.of(hostA, hostB));
        try {
            var a = new Node("a", "ga", null, null, "A", 0, 0, null, "telecom-site", List.of());
            var b = new Node("b", "gb", null, null, "B", 100, 0, null, "circle", List.of());
            var connection = new Connection("c", "Enlace", "ha", "ether1", "hb", "ether2");
            var link = new Link("a", "b", null, null, "straight", List.of(), "straight", "c", connection);
            var saved = service.save(new Saved(null, 0,
                new Layout("Grupos", null, 0, List.of(a, b), List.of(link)), null));
            assertThat(service.maps().stream().filter(map -> map.id().equals(saved.id())).findFirst().orElseThrow()
                .layout().links().getFirst().connection()).isEqualTo(connection);
            var directHost = new Node("b", null, "hb", null, "B", 100, 0, null);
            service.save(new Saved(null, 0,
                new Layout("Grupo e host", null, 0, List.of(a, directHost), List.of(link)), null));
            var wrongGroup = new Node("b", "ga", null, null, "B", 100, 0, null);
            assertThatThrownBy(() -> service.save(new Saved(null, 0,
                new Layout("Inválido", null, 0, List.of(a, wrongGroup), List.of(link)), null)))
                .isInstanceOf(ResponseStatusException.class);
        } finally {
            org.mockito.Mockito.reset(inventory);
        }
    }

    @Test
    void telecomSiteRequiresGroupBindingAndPersistsItsIcon() throws Exception {
        var node = new Node("site", "g1", null, null, "POP", 10, 20, null, "telecom-site", List.of());
        var saved = service.save(new Saved(null, 0,
            new Layout("Estação", null, 0, List.of(node), List.of()), null));
        assertThat(service.maps().stream().filter(map -> map.id().equals(saved.id())).findFirst().orElseThrow()
            .layout().nodes().getFirst().icon()).isEqualTo("telecom-site");
        for (var invalid : List.of(
            new Node("site", null, null, null, "POP", 0, 0, null, "telecom-site", List.of()),
            new Node("site", null, "host-a", null, "POP", 0, 0, null, "telecom-site", List.of()))) {
            assertThatThrownBy(() -> service.save(new Saved(null, 0,
                new Layout("Inválido", null, 0, List.of(invalid), List.of()), null)))
                .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    void persistsEquipmentTransformsAndDefaultsForOldMaps() throws Exception {
        var node = new Node("rotated", null, "host-a", null, "Rádio", 20, 30, null,
            "radio", List.of(), List.of(), false, null, null, 135.0, 1.5, true, new Origin(5, 10, 1.0));
        var saved = service.save(new Saved(null, 0,
            new Layout("Orientação", null, 0, List.of(node), List.of()), null));
        var restored = service.maps().stream().filter(m -> m.id().equals(saved.id())).findFirst().orElseThrow()
            .layout().nodes().getFirst();
        assertThat(restored.rotation()).isEqualTo(135.0);
        assertThat(restored.scale()).isEqualTo(1.5);
        assertThat(restored.flipX()).isTrue();
        assertThat(restored.origin()).isEqualTo(new Origin(5, 10, 1.0));
        var legacy = new ObjectMapper().readValue("{\"id\":\"old\",\"name\":\"Old\",\"x\":0,\"y\":0}", Node.class);
        assertThat(legacy.rotation()).isZero();
        assertThat(legacy.scale()).isEqualTo(1.0);
        assertThat(legacy.flipX()).isFalse();
        assertThat(legacy.origin()).isEqualTo(new Origin(0, 0, 1.0));
    }

    @Test
    void persistsWirelessConnectorAndStableInterfaceName() throws Exception {
        var slot = new Slot("wireless-slot", "Wireless", "unknown", 1, List.of("Wireless"), "wireless");
        var port = new Port("wireless-port", "wireless", slot.id(), "Wireless", "unknown", "", "wireless", "", true, "wireless");
        var radio = new Node("radio", null, "host-a", null, "Rádio", 0, 0, null,
            "radio", List.of(port), List.of(slot), true);
        var saved = service.save(new Saved(null, 0,
            new Layout("Enlace", null, 0, List.of(radio), List.of()), null));
        assertThat(saved.layout().nodes().getFirst().ports().getFirst().connector()).isEqualTo("wireless");
        assertThat(saved.layout().nodes().getFirst().ports().getFirst().name()).isEqualTo("Wireless");
    }

    @Test
    void rejectsMultipleWirelessInterfacesEvenWhenDisabled() {
        var ports = List.of(new Port("w1", "wireless"), new Port("w2", "wireless"));
        for (boolean enabled : List.of(true, false)) {
            var radio = new Node("radio", null, "host-a", null, "Rádio", 0, 0, null,
                "radio", ports, List.of(), enabled);
            assertThatThrownBy(() -> service.save(new Saved(null, 0,
                new Layout("Inválido", null, 0, List.of(radio), List.of()), null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("apenas uma interface Wireless");
        }
    }

    @Test
    void radioRequiresHostAndLimitsTotalInterfacesEvenWhenDisabled() throws Exception {
        var ports = java.util.stream.IntStream.range(0, 4)
            .mapToObj(i -> new Port("p" + i, "rj45")).toList();
        var radio = new Node("radio", null, "host-a", null, "Rádio", 0, 0, null,
            "radio", ports, List.of(), true);
        var saved = service.save(new Saved(null, 0,
            new Layout("Rádio", null, 0, List.of(radio), List.of()), null));
        assertThat(saved.layout().nodes().getFirst().icon()).isEqualTo("radio");
        var excess = new java.util.ArrayList<>(ports);
        excess.add(new Port("p4", "rj45"));
        for (var invalid : List.of(
            new Node("radio", null, "host-a", null, "Rádio", 0, 0, null, "radio", excess, List.of(), false),
            new Node("radio", null, null, null, "Rádio", 0, 0, null, "radio", ports))) {
            assertThatThrownBy(() -> service.save(new Saved(null, 0,
                new Layout("Inválido", null, 0, List.of(invalid), List.of()), null)))
                .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    void hierarchyAllowsNestedSubMapsAndRejectsMultipleParentsAndCycles() throws Exception {
        var leaf = service.save(new Saved(null, 0, new Layout("Folha", null, 0, List.of(), List.of()), null));
        var middle = mapWithSubMap("Intermediário", null, leaf.id());
        var root = mapWithSubMap("Principal", null, middle.id());
        assertThat(service.maps()).hasSize(3);
        assertThatThrownBy(() -> mapWithSubMap("Outro", null, leaf.id()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("mapa principal");
        assertThatThrownBy(() -> mapWithSubMap("Folha", leaf, root.id()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("ciclo");
    }

    @Test
    void rejectsTwoElementsPointingToTheSameSubMap() throws Exception {
        var child = service.save(new Saved(null, 0, new Layout("Filial", null, 0, List.of(), List.of()), null));
        var first = new Node("one", "g1", null, null, "Grupo 1", 0, 0, null,
            "circle", List.of(), List.of(), false, null, child.id());
        var second = new Node("two", "g2", null, null, "Grupo 2", 0, 0, null,
            "circle", List.of(), List.of(), false, null, child.id());
        assertThatThrownBy(() -> service.save(new Saved(null, 0,
            new Layout("Principal", null, 0, List.of(first, second), List.of()), null)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("mapa principal");
    }

    @Test
    void legacyPublicationIsIgnoredAndNotWrittenBack() throws Exception {
        var json = new ObjectMapper();
        for (var status : List.of("RASCUNHO", "PUBLICADO")) {
            var layout = json.readValue("{\"publication\":\"" + status
                + "\",\"name\":\"Legado\",\"nodes\":[],\"links\":[]}", Layout.class);
            var saved = service.save(new Saved(null, 0, layout, null));
            assertThat(json.writeValueAsString(saved.layout())).doesNotContain("publication", status);
        }
    }

    @Test
    void groupSubMapPersistsAndRejectsSelfReference() throws Exception {
        var child = service.save(new Saved(null, 0, new Layout("Filial", null, 0, List.of(), List.of()), null));
        var node = new Node("group", "g1", null, null, "Filial", 0, 0, null,
            "circle", List.of(), List.of(), false, null, child.id());
        var parent = service.save(new Saved(null, 0, new Layout("Principal", null, 0, List.of(node), List.of()), null));
        assertThat(service.maps().stream().filter(map -> map.id().equals(parent.id())).findFirst().orElseThrow()
            .layout().nodes().getFirst().subMapId()).isEqualTo(child.id());
        assertThatThrownBy(() -> service.save(new Saved(child.id(), child.revision(), parent.layout(), null)))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void connectorAndPortCapacityPersistIndependently() throws Exception {
        var slot = new Slot("slot", "sfp28-", "25g", 1, List.of("uplink"), "sfp28");
        var port = new Port("linked-port", "sfp", "slot", "uplink", "25g", "Core", "ether", "10Gbps", true, "sfp28");
        var node = new Node("sw", null, null, null, "Switch", 0, 0, null, "switch", List.of(port), List.of(slot));
        var saved = service.save(new Saved(null, 0, new Layout("Conectores", null, 0, List.of(node), List.of()), null));
        var restored = service.maps().getFirst();
        assertThat(restored).isEqualTo(saved);
        assertThat(restored.layout().nodes().getFirst().ports()).containsExactly(port);
        assertThat(restored.layout().nodes().getFirst().slots()).containsExactly(slot);
        var wrongConnector = new Port("linked-port", "sfp", "slot", "uplink", "25g", null, null, null, null, "sfpplus");
        var invalid = new Node("sw", null, null, null, "Switch", 0, 0, null, "switch", List.of(wrongConnector), List.of(slot));
        assertThatThrownBy(() -> service.save(new Saved(null, 0,
            new Layout("Inválido", null, 0, List.of(invalid), List.of()), null)))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void discoveredInterfacesKeepExactNamesAndMetadataAfterReload() throws Exception {
        var slots = List.of(new Slot("optical", "SFP10G", "sfp10g", 1, List.of("uplink-core")));
        var port = new Port("connected", "sfp", "optical", "uplink-core", "sfp10g", "Core", "ether", "10Gbps", true);
        var node = new Node("sw", null, null, null, "Switch", 0, 0, null, "switch", List.of(port), slots);
        var saved = service.save(new Saved(null, 0, new Layout("Descoberta", null, 0, List.of(node), List.of()), null));
        assertThat(service.maps()).contains(saved);
        assertThat(service.maps().getFirst().layout().nodes().getFirst().ports()).containsExactly(port);
        var invalid = new Node("sw", null, null, null, "Switch", 0, 0, null, "switch", List.of(port),
            List.of(new Slot("optical", "SFP10G", "sfp10g", 1, List.of("different"))));
        assertThatThrownBy(() -> service.save(new Saved(null, 0,
            new Layout("Inválido", null, 0, List.of(invalid), List.of()), null)))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void connectionDataBelongsToMapAndMustMatchEndpointHosts() throws Exception {
        var nodes = List.of(new Node("a", null, "h1", null, "A", 0, 0, null), new Node("b", null, "h2", null, "B", 200, 0, null));
        var connection = new Connection("c1", "Cabo", "h1", "ether1", "h2", "ether2");
        var link = new Link("a", "b", null, null, "straight", List.of(), "straight", "c1", connection);
        var saved = service.save(new Saved(null, 0, new Layout("Rede", null, 0, nodes, List.of(link)), null));
        assertThat(service.maps().getFirst().layout().links().getFirst().connection()).isEqualTo(connection);
        var copy = service.save(new Saved(null, 0, saved.layout(), null));
        var changed = new Connection("c1", "Alterado", "h1", "ether3", "h2", "ether4");
        var editedLink = new Link("a", "b", null, null, "straight", List.of(), "straight", "c1", changed);
        service.save(new Saved(saved.id(), saved.revision(), new Layout("Rede", null, 0, nodes, List.of(editedLink)), null));
        assertThat(service.maps().stream().filter(m -> m.id().equals(copy.id())).findFirst().orElseThrow().layout().links().getFirst().connection()).isEqualTo(connection);
        var invalid = new Link("b", "a", null, null, "straight", List.of(), "straight", "c1", connection);
        assertThatThrownBy(() -> service.save(new Saved(null, 0, new Layout("Inválido", null, 0, nodes, List.of(invalid)), null))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void createsAndUpdatesMapsRejectingStaleOrMissingRevisions() throws Exception {
        var layout = new Layout("Rede", null, null, List.of(), List.of());
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
        var layout = new Layout("Rede", null, 0, false, List.of(), List.of());
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
            new Layout("Portas", null, 0, List.of(a, b), links), null));
        assertThat(service.maps()).contains(saved);
        for (var invalid : List.of(
            List.of(new Link("a", "b", "missing", "b1")),
            List.of(new Link("a", "b", "a1", "b1"), new Link("a", "b", "a1", "b2")),
            List.of(new Link("a", "b", "a1", "b1"), new Link("b", "a", "b1", "a1")))) {
            assertThatThrownBy(() -> service.save(new Saved(null, 0,
                new Layout("Inválido", null, 0, List.of(a, b), invalid), null)))
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
            new Layout("Caminho", null, 0, List.of(a, b), List.of(link)), null));
        assertThat(service.maps()).contains(saved);
        assertThat(saved.layout().links().getFirst().points()).isEqualTo(bends);
        assertThat(saved.layout().links().getFirst().lineStyle()).isEqualTo("curved");
        for (var invalid : List.of(new Link("a", "b", null, null, "corners", List.of(), "invalid"),
                new Link("a", "b", null, null, "unknown", List.of()),
                new Link("a", "b", null, null, "corners", List.of(new Bend(Double.NaN, 0))))) {
            assertThatThrownBy(() -> service.save(new Saved(null, 0,
                new Layout("Inválido", null, 0, List.of(a, b), List.of(invalid)), null)))
                .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    void persistsMultipleInterfaceSlotsAndRejectsInconsistentPorts() throws Exception {
        var slots = List.of(new Slot("slot1", "ether", "electrical", 1), new Slot("slot2", "1/1/", "sfp100g", 1));
        var ports = List.of(new Port("p1", "rj45", "slot1", "ether1", "electrical"),
            new Port("p2", "qsfp", "slot2", "1/1/1", "sfp100g"));
        var node = new Node("sw", null, null, null, "Switch", 0, 0, null, "switch", ports, slots);
        var saved = service.save(new Saved(null, 0, new Layout("Slots", null, 0, List.of(node), List.of()), null));
        assertThat(service.maps()).contains(saved);
        assertThat(saved.layout().nodes().getFirst().slots()).isEqualTo(slots);
        var invalid = new Node("bad", null, null, null, "Switch", 0, 0, null, "switch", ports,
            List.of(new Slot("slot1", "ether", "electrical", 2)));
        assertThatThrownBy(() -> service.save(new Saved(null, 0,
            new Layout("Inválido", null, 0, List.of(invalid), List.of()), null)))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void persistsInterfaceEnablementIndependentlyOfIconAndPreservesHiddenSlots() throws Exception {
        var slots = List.of(new Slot("s", "ether", "electrical", 1));
        var ports = List.of(new Port("p", "rj45", "s", "ether1", "electrical"));
        var circle = new Node("a", null, null, null, "Circle", 0, 0, null, "circle", ports, slots, true);
        var disabled = new Node("b", null, null, null, "Switch", 200, 0, null, "switch", ports, slots, false);
        var saved = service.save(new Saved(null, 0, new Layout("Interfaces", null, 0,
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
        var saved = service.save(new Saved(null, 0, new Layout("Cor", null, 0, List.of(node), List.of()), null));
        assertThat(service.maps()).contains(saved);
        assertThat(saved.layout().nodes().getFirst().switchColor()).isEqualTo("#2288cc");
        assertThat(saved.layout().nodes().getFirst().color()).isEqualTo("#123456");
        var invalid = new Node("bad", null, null, null, "Switch", 0, 0, null, "switch",
            List.of(), List.of(), false, "red");
        assertThatThrownBy(() -> service.save(new Saved(null, 0,
            new Layout("Cor inválida", null, 0, List.of(invalid), List.of()), null)))
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
        var layout = new Layout("", null, null, List.of(), List.of());
        assertThatThrownBy(() -> service.save(new Saved(null, 0, layout, null)))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
        assertThat(repository.count()).isZero();
    }
}
