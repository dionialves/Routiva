package br.com.scriptping.availability;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class HostDetailsController {

    private final HostAvailabilityService availability;

    public HostDetailsController(HostAvailabilityService availability) {
        this.availability = availability;
    }

    private HostAvailabilityService.Report report(String id, int hours) {
        try {
            return availability.report(id, hours);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/api/hosts/{id}/availability")
    @ResponseBody
    public org.springframework.http.ResponseEntity<HostAvailabilityService.Report> data(
        @PathVariable String id,
        @RequestParam(defaultValue = "24") int hours
    ) {
        return org.springframework.http.ResponseEntity.ok()
            .cacheControl(org.springframework.http.CacheControl.noStore())
            .body(report(id, hours));
    }
}
