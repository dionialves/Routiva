package br.com.scriptping.maps;

import br.com.scriptping.zabbix.Host;
import br.com.scriptping.zabbix.ZabbixClient;
import java.util.List;
import java.util.Set;

public final class NetworkMapModels {
    private NetworkMapModels() {}

    public enum LabelPosition { ABOVE, BELOW, LEFT, RIGHT }

    public record Node(String id, String groupId, String hostId, String color, String name, int x, int y, LabelPosition labelPosition, String icon, List<Port> ports, List<Slot> slots, Boolean interfacesEnabled, String switchColor, String subMapId, Double rotation, Double scale, Boolean flipX, Origin origin, List<LinkInterface> linkInterfaces, String observation) {
        public Node(String id, String groupId, String hostId, String color, String name, int x, int y, LabelPosition labelPosition, String icon, List<Port> ports, List<Slot> slots, Boolean interfacesEnabled, String switchColor, String subMapId, Double rotation, Double scale, Boolean flipX, Origin origin, List<LinkInterface> linkInterfaces) {
            this(id, groupId, hostId, color, name, x, y, labelPosition, icon, ports, slots, interfacesEnabled, switchColor, subMapId, rotation, scale, flipX, origin, linkInterfaces, null);
        }
        public Node(String id, String groupId, String hostId, String color, String name, int x, int y, LabelPosition labelPosition, String icon, List<Port> ports, List<Slot> slots, Boolean interfacesEnabled, String switchColor, String subMapId, Double rotation, Double scale, Boolean flipX, Origin origin) {
            this(id, groupId, hostId, color, name, x, y, labelPosition, icon, ports, slots, interfacesEnabled, switchColor, subMapId, rotation, scale, flipX, origin, null);
        }
        public Node(String id, String groupId, String hostId, String color, String name, int x, int y, LabelPosition labelPosition, String icon, List<Port> ports, List<Slot> slots, Boolean interfacesEnabled, String switchColor, String subMapId, Double rotation, Double scale, Boolean flipX) {
            this(id, groupId, hostId, color, name, x, y, labelPosition, icon, ports, slots, interfacesEnabled, switchColor, subMapId, rotation, scale, flipX, null);
        }
        public Node(String id, String groupId, String hostId, String color, String name, int x, int y, LabelPosition labelPosition, String icon, List<Port> ports, List<Slot> slots, Boolean interfacesEnabled, String switchColor, String subMapId) {
            this(id, groupId, hostId, color, name, x, y, labelPosition, icon, ports, slots, interfacesEnabled, switchColor, subMapId, 0.0, 1.0, false);
        }
        public Node(String id, String groupId, String hostId, String color, String name, int x, int y, LabelPosition labelPosition, String icon, List<Port> ports, List<Slot> slots, Boolean interfacesEnabled, String switchColor) {
            this(id, groupId, hostId, color, name, x, y, labelPosition, icon, ports, slots, interfacesEnabled, switchColor, null);
        }
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
            if (rotation == null) rotation = 0.0;
            if (scale == null) scale = 1.0;
            if (flipX == null) flipX = false;
            if (origin == null) origin = new Origin(x, y, scale);
            // Existing maps identify link endpoints by their host group.
            if (id == null) id = groupId;
            if (icon == null) icon = "circle";
            if (ports == null) ports = List.of();
            if (slots == null) slots = List.of();
            if (interfacesEnabled == null) interfacesEnabled = !ports.isEmpty() || !slots.isEmpty();
            if (labelPosition == null) labelPosition = LabelPosition.BELOW;
        }
    }
    public record LinkInterface(String hostId, String name, String connector, String capacity) {}
    public record Origin(int x, int y, double scale) {}
    public record Slot(String id, String name, String capacity, int count, List<String> interfaceNames, String connector) {
        public Slot(String id, String name, String capacity, int count, List<String> interfaceNames) { this(id, name, capacity, count, interfaceNames, null); }
        public Slot(String id, String name, String capacity, int count) { this(id, name, capacity, count, null); }
    }
    public record Port(String id, String type, String slotId, String name, String capacity, String comment, String interfaceType, String speed, Boolean running, String connector) {
        public Port(String id, String type, String slotId, String name, String capacity, String comment, String interfaceType, String speed, Boolean running) { this(id, type, slotId, name, capacity, comment, interfaceType, speed, running, null); }
        public Port(String id, String type, String slotId, String name, String capacity) { this(id, type, slotId, name, capacity, null, null, null, null); }
        public Port(String id, String type) { this(id, type, null, null, null); }
    }
    public record Connection(String id, String name, String hostA, String interfaceA, String hostB, String interfaceB) {}
    public record Bend(double x, double y) {}
    public record Link(String from, String to, String fromPort, String toPort, String style, List<Bend> points, String lineStyle, String mappedConnectionId, Connection connection) {
        public Link(String from, String to, String fromPort, String toPort, String style, List<Bend> points, String lineStyle, String mappedConnectionId) {
            this(from, to, fromPort, toPort, style, points, lineStyle, mappedConnectionId, null);
        }
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
    public record Camera(double x, double y, double scale) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"width", "height", "publication"})
    public record Layout(String name, Camera camera, Integer minimumSeverity, Boolean showAcknowledged, List<Node> nodes, List<Link> links) {
        public Layout(String name, Camera camera, Integer minimumSeverity, List<Node> nodes, List<Link> links) {
            this(name, camera, minimumSeverity, true, nodes, links);
        }

        public Layout {
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
