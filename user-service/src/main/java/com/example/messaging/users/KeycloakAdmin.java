package com.example.messaging.users;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
class KeycloakAdmin {
    private static final List<String> SETUP_ACTIONS = List.of("VERIFY_EMAIL", "UPDATE_PASSWORD");
    private static final ParameterizedTypeReference<List<Identity>> IDENTITIES =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    KeycloakAdmin(@Value("${app.keycloak.admin-base-url}") URI baseUrl,
                  @Value("${app.keycloak.realm}") String realm,
                  @Value("${app.keycloak.client-id}") String clientId,
                  @Value("${app.keycloak.client-secret}") String clientSecret) {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().baseUrl(baseUrl.toString()).requestFactory(requestFactory).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    Optional<String> provision(String username, String email, String firstName, String lastName) {
        String token = token();
        var existing = find(token, "username", username);
        if (!existing.isEmpty()) {
            return Optional.of(subject(existing, username, email));
        }

        try {
            client.post().uri("/admin/realms/{realm}/users", realm)
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("username", username, "email", email, "firstName", firstName,
                            "lastName", lastName, "enabled", true, "emailVerified", false,
                            "requiredActions", SETUP_ACTIONS))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException conflict) {
            if (conflict.getStatusCode().value() != 409) {
                throw conflict;
            }
            var recovered = find(token, "username", username);
            if (!recovered.isEmpty()) {
                return Optional.of(subject(recovered, username, email));
            }
            if (find(token, "email", email).stream().anyMatch(user -> email.equalsIgnoreCase(user.email()))) {
                return Optional.empty();
            }
            throw conflict;
        }

        return Optional.of(subject(find(token, "username", username), username, email));
    }

    void sendSetupEmail(String subject) {
        client.put().uri("/admin/realms/{realm}/users/{subject}/execute-actions-email", realm, subject)
                .headers(headers -> headers.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(SETUP_ACTIONS)
                .retrieve().toBodilessEntity();
    }

    private List<Identity> find(String token, String field, String value) {
        var identities = client.get()
                .uri(uri -> uri.path("/admin/realms/{realm}/users")
                        .queryParam(field, "{value}").queryParam("exact", true).build(realm, value))
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve().body(IDENTITIES);
        if (identities == null) {
            throw new RestClientException("Keycloak returned no user search response");
        }
        return identities;
    }

    private static String subject(List<Identity> users, String username, String email) {
        if (users.size() != 1) {
            throw new RestClientException("Could not resolve the provisioned Keycloak identity");
        }
        var user = users.getFirst();
        if (!username.equals(user.username()) || !email.equalsIgnoreCase(user.email()) || user.id() == null) {
            throw new RestClientException("The provisioned Keycloak identity did not match");
        }
        return user.id();
    }

    private String token() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        var token = client.post().uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(Token.class);
        if (token == null || token.access_token() == null || token.access_token().isBlank()) {
            throw new RestClientException("Keycloak returned no service account token");
        }
        return token.access_token();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Token(String access_token) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Identity(String id, String username, String email) {}
}
