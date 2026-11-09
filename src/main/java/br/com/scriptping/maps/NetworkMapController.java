package br.com.scriptping.maps;

import br.com.scriptping.zabbix.Host;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

import static br.com.scriptping.maps.NetworkMapModels.*;

@RestController
@RequestMapping("/api/maps")
public class NetworkMapController {
    private final NetworkMapService service;

    public NetworkMapController(NetworkMapService service) {
        this.service = service;
    }

    @GetMapping
    public List<Saved> maps() {
        return service.maps();
    }

    @GetMapping("/groups")
    public List<Host.Group> groups() {
        return service.groups();
    }

    @GetMapping("/hosts")
    public List<HostOption> hosts() {
        return service.hosts();
    }

    @PostMapping("/status")
    public List<HostState> status(@RequestBody Set<String> groups) {
        return service.status(groups);
    }

    @PostMapping("/states")
    public List<HostState> states(@RequestBody Targets targets) {
        return service.states(targets);
    }

    @PostMapping
    public Saved save(@RequestBody Saved input) throws JsonProcessingException {
        return service.save(input);
    }
}
