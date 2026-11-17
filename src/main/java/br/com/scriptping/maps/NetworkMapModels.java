package br.com.scriptping.maps;

import br.com.scriptping.zabbix.Host;
import br.com.scriptping.zabbix.ZabbixClient;
import java.util.List;
import java.util.Set;

public final class NetworkMapModels {
    private NetworkMapModels() {}

    public enum LabelPosition { ABOVE, BELOW, LEFT, RIGHT }

    public record Node(String id, String groupId, String hostId, String color, String name, int x, int y, LabelPosition labelPosition, String icon, List<Port> ports, List<Slot> slots, Boolean interfacesEnabled, String switchColor) {
        public Node(String id, String groupId, String hostId, String color, String name, int x, int y, LabelPosition labelPosition, String icon, List<Port> ports, List<Slot> slots, Boolean interfacesEnabled) {
            this(id, groupId, hostId, color, name, x, y, labelPosition, icon, ports, slots, interfacesEnabled, null);
        }
        public Node(String id, String groupId, String hostId, String color, String name, int x, int y, LabelPosition labelPosition, String icon, List<Port> ports, List<Slot> slots) {
            this(id, groupId, hostId, color, name, x, y, labelPosition, icon, ports, slots, null);
        }
        public Node(String id, String groupId, String hostId, String color, String name, int x, int y, LabelPosition labelPosition, String icon, List<Port> ports) {
            this(id, groupId, hostId, color, name, x, y, labelPosition, icon, ports, List.of());
        }
        public Node(String id, String groupId, String hostId, String color, String name, int x, int y, LabelPosition labelPosition) {
            this(id, groupId, hostId, color, name, x, y, labelPosition, "circle", List.of());
        }
        public Node {
            // Existing maps identify link endpoints by their host group.
            if (id == null) id = groupId;
            if (icon == null) icon = "circle";
            if (ports == null) ports = List.of();
            if (slots == null) slots = List.of();
            if (interfacesEnabled == null) interfacesEnabled = !ports.isEmpty() || !slots.isEmpty();
            if (labelPosition == null) labelPosition = LabelPosition.BELOW;
        }
    }
    public record Slot(String id, String name, String capacity, int count) {}
    public record Port(String id, String type, String slotId, String name, String capacity) {
        public Port(String id, String type) { this(id, type, null, null, null); }
    }
    public record Bend(double x, double y) {}
    public record Link(String from, String to, String fromPort, String toPort, String style, List<Bend> points, String lineStyle, String mappedConnectionId) {
        public Link(String from, String to, String fromPort, String toPort, String style, List<Bend> points, String lineStyle) {
            this(from, to, fromPort, toPort, style, points, lineStyle, null);
        }
        public Link(String from, String to, String fromPort, String toPort, String style, List<Bend> points) {
            this(from, to, fromPort, toPort, style, points, "straight");
        }
        public Link(String from, String to) { this(from, to, null, null); }
        public Link(String from, String to, String fromPort, String toPort) {
            this(from, to, fromPort, toPort, "straight", List.of());
        }
        public Link {
            if (style == null) style = "straight";
            if (lineStyle == null) lineStyle = "straight";
            if (points == null) points = List.of();
        }
    }
    public enum Publication { RASCUNHO, PUBLICADO }

    public record Camera(double x, double y, double scale) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"width", "height"})
    public record Layout(Publication publication, String name, Camera camera, Integer minimumSeverity, Boolean showAcknowledged, List<Node> nodes, List<Link> links) {
        public Layout(Publication publication, String name, Camera camera, Integer minimumSeverity, List<Node> nodes, List<Link> links) {
            this(publication, name, camera, minimumSeverity, true, nodes, links);
        }

        public Layout {
            if (publication == null) publication = Publication.PUBLICADO;
            if (minimumSeverity == null) minimumSeverity = 0;
            if (showAcknowledged == null) showAcknowledged = true;
        }
    }
    public record Saved(String id, long revision, Layout layout, Long updatedAt) {}

    public record HostOption(String id, String name, String ip, List<Host.Group> groups) {}
    public record Targets(Set<String> groups, Set<String> hosts) {}
    public record HostState(String id, String name, List<Host.Group> groups, List<ZabbixClient.Incident> incidents, String monitoring) {
        public HostState(String id, String name, List<Host.Group> groups, List<ZabbixClient.Incident> incidents) {
            this(id, name, groups, incidents, "unknown");
        }
    }
}
