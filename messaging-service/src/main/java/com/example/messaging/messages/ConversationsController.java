package com.example.messaging.messages;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/conversations")
class ConversationsController {
    private final ConversationService conversations;
    private final MessageService messages;

    ConversationsController(ConversationService conversations, MessageService messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    @PostMapping
    ConversationService.ConversationView open(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OpenConversation request) {
        return conversations.open(subject(jwt), request.recipientSubject(), jwt.getTokenValue());
    }

    @GetMapping
    List<ConversationService.ConversationView> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return conversations.list(subject(jwt), page, limit);
    }

    @PostMapping("/{id}/messages")
    MessageService.MessageView send(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody SendMessage request) {
        return messages.send(id, subject(jwt), request.clientMessageId(), request.body());
    }

    @GetMapping("/{id}/messages")
    MessageService.MessagePage history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(required = false) UUID before,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return messages.history(id, subject(jwt), before, limit);
    }

    private static String subject(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return subject;
    }

    record OpenConversation(@NotBlank @Size(max = 255) String recipientSubject) {}
    record SendMessage(@NotNull UUID clientMessageId, @NotBlank @Size(max = 4000) String body) {}
}
