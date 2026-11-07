package com.example.messaging.messages;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
class UserDirectory {
    private final RestClient client;

    UserDirectory(@Value("${app.users.base-url}") URI baseUrl) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = RestClient.builder().baseUrl(baseUrl.toString()).requestFactory(factory).build();
    }

    void requireProfile(String subject, String accessToken) {
        try {
            var response = client.get()
                    .uri("/api/users/exists?subject={subject}", subject)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve().toBodilessEntity();
            if (response.getStatusCode() != HttpStatus.NO_CONTENT) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "User directory unavailable");
            }
        } catch (HttpClientErrorException.NotFound missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipient not found");
        } catch (RestClientException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "User directory unavailable");
        }
    }
}
