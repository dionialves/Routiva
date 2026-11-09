package br.com.scriptping.availability;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    @GetMapping("/mapas")
    public String maps() { return "maps"; }

    @GetMapping("/")
    public String home() {
        return "redirect:/disponibilidade";
    }

    @GetMapping("/disponibilidade")
    public String availability() {
        return "availability";
    }
}
