package com.petroxpert.ms.presentation;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import java.util.Map;

@Controller
@Profile("v1")
public class ClientController {
    @GetMapping({"/", "/markets", "/closings", "/reports", "/cities"})
    public String client() { return "forward:/index.html"; }

    @GetMapping("/api/v1/health")
    @ResponseBody
    public Map<String, String> health() { return Map.of("status", "UP"); }
}
