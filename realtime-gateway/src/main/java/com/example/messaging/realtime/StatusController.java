package com.example.messaging.realtime;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatusController {
    @GetMapping("/api/realtime/status")
    Map<String, String> status(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("service", "realtime-gateway", "subject", jwt.getSubject());
    }
}
