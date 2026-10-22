package br.com.scriptping;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Controller
public class HostDetailsController {
 private final MonitorService monitor;private final HostAvailabilityService availability;
 public HostDetailsController(MonitorService monitor,HostAvailabilityService availability){this.monitor=monitor;this.availability=availability;}
 private Host host(String id){return monitor.hosts().stream().filter(h->h.id().equals(id)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));}
 @GetMapping("/hosts/{id}") public String page(@PathVariable String id,@RequestParam(defaultValue="24") int hours,Model model){
  Host host=host(id);model.addAttribute("host",host);model.addAttribute("hours",hours);
  model.addAttribute("periods",new int[]{1,2,4,8,12,24,48});model.addAttribute("report",report(host,hours));return "host";
 }
 private HostAvailabilityService.Report report(Host host,int hours){try{return availability.report(host,hours,System.currentTimeMillis());}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}}
 @GetMapping("/api/hosts/{id}/availability") @ResponseBody public HostAvailabilityService.Report data(@PathVariable String id,@RequestParam(defaultValue="24") int hours){return report(host(id),hours);}
}
