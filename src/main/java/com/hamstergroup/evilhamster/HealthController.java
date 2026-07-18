package com.hamstergroup.evilhamster;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping({"/", "/health", "/healthz", "/ready"})
    public String health() {
        return "OK";
    }
}
