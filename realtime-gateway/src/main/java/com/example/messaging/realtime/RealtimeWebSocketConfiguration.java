package com.example.messaging.realtime;

import java.time.Instant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
class RealtimeWebSocketConfiguration implements WebSocketConfigurer {
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new PingHandler(), "/ws");
    }

    @Bean
    ServletServerContainerFactoryBean webSocketLimits() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(256);
        container.setMaxBinaryMessageBufferSize(256);
        container.setMaxSessionIdleTimeout(60_000L);
        return container;
    }

    private static class PingHandler extends TextWebSocketHandler {
        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            if (!(session.getPrincipal() instanceof JwtAuthenticationToken authentication)) {
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            var expiry = authentication.getToken().getExpiresAt();
            if (expiry == null || !expiry.isAfter(Instant.now()) || !"ping".equals(message.getPayload())) {
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            session.sendMessage(new TextMessage("pong"));
        }
    }
}
