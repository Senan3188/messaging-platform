package com.example.messaging.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

class KeycloakAdminTest {
    private static final String USERNAME = "registration-generated";
    private static final String EMAIL = "test+alias@example.com";
    private static final String USERS_PATH = "/admin/realms/messaging/users";
    private static final String IDENTITY = """
            [{"id":"subject-1","username":"registration-generated","email":"test+alias@example.com"}]
            """;

    private HttpServer server;
    private KeycloakAdmin admin;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/messaging/protocol/openid-connect/token",
                request -> respond(request, 200, """
                        {"access_token":"service-token","expires_in":60}
                        """));
        server.start();
        admin = new KeycloakAdmin(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "messaging", "user-provisioner", "test-secret");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void retriesAnAmbiguousCreateByRecoveringTheOriginalIdentity() {
        var creates = new AtomicInteger();
        server.createContext(USERS_PATH, request -> {
            if (request.getRequestMethod().equals("POST")) {
                creates.incrementAndGet();
                // The identity was saved before the upstream reported failure.
                respond(request, 500, "{}");
                return;
            }
            respond(request, 200, creates.get() == 0 ? "[]" : IDENTITY);
        });

        assertThatThrownBy(() -> admin.provision(USERNAME, EMAIL, "Ada", "Lovelace"))
                .isInstanceOf(RestClientException.class);
        assertThat(admin.provision(USERNAME, EMAIL, "Ada", "Lovelace")).contains("subject-1");
        assertThat(creates.get()).isEqualTo(1);
    }

    @Test
    void neverAdoptsAnUnrelatedAccountWithTheSameEmail() {
        server.createContext(USERS_PATH, request -> {
            if (request.getRequestMethod().equals("POST")) {
                respond(request, 409, "{}");
                return;
            }
            boolean emailSearch = request.getRequestURI().getRawQuery().contains("email=test%2Balias");
            respond(request, 200, emailSearch ? """
                    [{"id":"somebody-else","username":"existing","email":"test+alias@example.com"}]
                    """ : "[]");
        });

        assertThat(admin.provision(USERNAME, EMAIL, "Ada", "Lovelace")).isEmpty();
    }

    @Test
    void rejectsAnIdentityWithUnexpectedEmail() {
        server.createContext(USERS_PATH,
                request -> respond(request, 200, IDENTITY.replace(EMAIL, "other@example.com")));

        assertThatThrownBy(() -> admin.provision(USERNAME, EMAIL, "Ada", "Lovelace"))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("did not match");
    }

    private static void respond(HttpExchange request, int status, String body) throws IOException {
        try (request) {
            request.getRequestBody().readAllBytes();
            byte[] json = body.getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().add("Content-Type", "application/json");
            request.sendResponseHeaders(status, json.length);
            request.getResponseBody().write(json);
        }
    }
}
