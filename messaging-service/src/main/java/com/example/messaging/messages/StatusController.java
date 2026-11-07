package com.example.messaging.messages;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatusController {
    @GetMapping("/api/messages/status")
    Map<String, String> status(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("service", "messaging-service", "subject", jwt.getSubject());
    }
}
