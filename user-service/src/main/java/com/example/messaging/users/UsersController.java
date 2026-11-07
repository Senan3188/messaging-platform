package com.example.messaging.users;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/users")
class UsersController {
    private static final Logger log = LoggerFactory.getLogger(UsersController.class);
    private final RegistrationService registrations;
    private final JdbcClient db;

    UsersController(RegistrationService registrations, JdbcClient db) {
        this.registrations = registrations;
        this.db = db;
    }

    @PostMapping("/register")
    ResponseEntity<Void> register(@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Signup signup) {
        try {
            registrations.register(key, signup.email(), signup.firstName(), signup.lastName());
        } catch (DataAccessException | TransactionException | RestClientException unavailable) {
            // Exception bodies may contain identity details. Log only the exception type and upstream status.
            if (unavailable instanceof RestClientResponseException upstream) {
                log.warn("Keycloak registration request failed with HTTP {}", upstream.getStatusCode().value());
            } else {
                log.warn("Registration could not complete ({})", unavailable.getClass().getSimpleName());
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5").build();
        }
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/exists")
    ResponseEntity<Void> exists(@RequestParam String subject) {
        boolean exists = db.sql("SELECT EXISTS (SELECT 1 FROM profiles WHERE subject = :subject)")
                .param("subject", subject).query(Boolean.class).single();
        return exists ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/me")
    Profile me(@AuthenticationPrincipal Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return db.sql("SELECT subject, display_name, created_at FROM profiles WHERE subject = :subject")
                .param("subject", subject)
                .query((row, index) -> new Profile(row.getString("subject"), row.getString("display_name"),
                        row.getObject("created_at", OffsetDateTime.class)))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    record Signup(@NotBlank @Email @Size(max = 254) String email,
                  @NotBlank @Size(max = 80) String firstName,
                  @NotBlank @Size(max = 80) String lastName) {}
    record Profile(String subject, String displayName, OffsetDateTime createdAt) {}
}
