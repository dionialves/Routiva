package br.com.scriptping.maps;

import br.com.scriptping.zabbix.Host;
import br.com.scriptping.zabbix.ZabbixClient;
import java.util.List;
import java.util.Set;

public final class NetworkMapModels {
    private NetworkMapModels() {}

    public enum LabelPosition { ABOVE, BELOW, LEFT, RIGHT }

    public record Node(String id, String groupId, String hostId, String color, String name, int x, int y, LabelPosition labelPosition) {
        public Node {
            // Existing maps identify link endpoints by their host group.
            if (id == null) id = groupId;
            if (labelPosition == null) labelPosition = LabelPosition.BELOW;
        }
    }
    public record Link(String from, String to) {}
    public enum Publication { RASCUNHO, PUBLICADO }

    public record Camera(double x, double y, double scale) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"width", "height"})
    public record Layout(Publication publication, String name, Camera camera, Integer minimumSeverity, List<Node> nodes, List<Link> links) {
        public Layout {
            if (publication == null) publication = Publication.PUBLICADO;
            if (minimumSeverity == null) minimumSeverity = 0;
        }
    }
    public record Saved(String id, long revision, Layout layout, Long updatedAt) {}

    public record HostOption(String id, String name, String ip) {}
    public record Targets(Set<String> groups, Set<String> hosts) {}
    public record HostState(String id, String name, List<Host.Group> groups, List<ZabbixClient.Incident> incidents, String monitoring) {
        public HostState(String id, String name, List<Host.Group> groups, List<ZabbixClient.Incident> incidents) {
            this(id, name, groups, incidents, "unknown");
        }
    }
}
