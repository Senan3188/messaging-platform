package com.example.messaging.users;

import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class RegistrationService {
    private final JdbcClient db;
    private final TransactionTemplate transaction;
    private final KeycloakAdmin keycloak;

    RegistrationService(JdbcClient db, TransactionTemplate transaction, KeycloakAdmin keycloak) {
        this.db = db;
        this.transaction = transaction;
        this.keycloak = keycloak;
    }

    void register(UUID key, String email, String firstName, String lastName) {
        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        String normalizedFirstName = firstName.strip();
        String normalizedLastName = lastName.strip();

        // Commit the stable identity name before calling Keycloak so an ambiguous create can be recovered.
        db.sql("""
                INSERT INTO registrations (idempotency_key, identity_name, email, first_name, last_name)
                VALUES (:key, :identity, :email, :first, :last)
                ON CONFLICT DO NOTHING
                """)
                .param("key", key).param("identity", UUID.randomUUID()).param("email", normalizedEmail)
                .param("first", normalizedFirstName).param("last", normalizedLastName).update();

        boolean needsEmail = Boolean.TRUE.equals(transaction.execute(status -> {
            var registration = lock(key);
            if (registration == null) {
                // Another key reserved this email. Give the same public response.
                return false;
            }
            if (!registration.email().equals(normalizedEmail)
                    || !registration.firstName().equals(normalizedFirstName)
                    || !registration.lastName().equals(normalizedLastName)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key already used");
            }
            if (registration.finished()) {
                return false;
            }
            if (registration.subject() != null) {
                return true;
            }

            var subject = keycloak.provision("registration-" + registration.identityName(),
                    normalizedEmail, normalizedFirstName, normalizedLastName);
            if (subject.isEmpty()) {
                // An existing Keycloak identity owns the email. Never attach a profile to it.
                finish(key);
                return false;
            }
            db.sql("INSERT INTO profiles (subject, display_name) VALUES (:subject, :name)")
                    .param("subject", subject.get())
                    .param("name", normalizedFirstName + " " + normalizedLastName).update();
            db.sql("UPDATE registrations SET subject = :subject WHERE idempotency_key = :key")
                    .param("subject", subject.get()).param("key", key).update();
            return true;
        }));

        if (needsEmail) {
            transaction.executeWithoutResult(status -> {
                var registration = lock(key);
                if (!registration.finished()) {
                    keycloak.sendSetupEmail(registration.subject());
                    finish(key);
                }
            });
        }
    }

    private Registration lock(UUID key) {
        db.sql("SET LOCAL lock_timeout = '2s'").update();
        return db.sql("""
                SELECT identity_name, email, first_name, last_name, subject, finished_at IS NOT NULL AS finished
                FROM registrations WHERE idempotency_key = :key FOR UPDATE
                """)
                .param("key", key)
                .query((row, index) -> new Registration(row.getObject("identity_name", UUID.class),
                        row.getString("email"), row.getString("first_name"), row.getString("last_name"),
                        row.getString("subject"), row.getBoolean("finished")))
                .optional().orElse(null);
    }

    private void finish(UUID key) {
        db.sql("UPDATE registrations SET finished_at = now() WHERE idempotency_key = :key")
                .param("key", key).update();
    }

    private record Registration(UUID identityName, String email, String firstName, String lastName,
                                String subject, boolean finished) {}
}
