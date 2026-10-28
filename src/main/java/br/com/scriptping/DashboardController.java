package br.com.scriptping;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    @GetMapping("/")
    public String home() {
        return "redirect:/disponibilidade";
    }

    @GetMapping("/disponibilidade")
    public String availability() {
        return "availability";
    }
}
