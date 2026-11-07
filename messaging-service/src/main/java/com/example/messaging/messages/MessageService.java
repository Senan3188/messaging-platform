package com.example.messaging.messages;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class MessageService {
    private final MessageRepository messages;
    private final ConversationService conversations;

    MessageService(MessageRepository messages, ConversationService conversations) {
        this.messages = messages;
        this.conversations = conversations;
    }

    @Transactional
    MessageView send(UUID conversationId, String sender, UUID clientMessageId, String body) {
        conversations.requireParticipant(conversationId, sender);
        messages.insertIfAbsent(UUID.randomUUID(), conversationId, sender, clientMessageId, body);

        var message = messages.findByConversationIdAndSenderSubjectAndClientMessageId(
                conversationId, sender, clientMessageId).orElseThrow();
        if (!message.body().equals(body)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Client message ID already used");
        }
        return view(message);
    }

    @Transactional(readOnly = true)
    MessagePage history(UUID conversationId, String subject, UUID before, int limit) {
        conversations.requireParticipant(conversationId, subject);
        List<Message> rows;
        if (before == null) {
            rows = messages.findByConversationIdOrderByCreatedAtDescIdDesc(conversationId,
                    PageRequest.of(0, limit + 1));
        } else {
            var cursor = messages.findByIdAndConversationId(before, conversationId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid message cursor"));
            rows = messages.findBefore(conversationId, cursor.createdAt(), cursor.id(), limit + 1);
        }
        UUID nextBefore = rows.size() > limit ? rows.get(limit - 1).id() : null;
        return new MessagePage(rows.stream().limit(limit).map(MessageService::view).toList(), nextBefore);
    }

    private static MessageView view(Message message) {
        return new MessageView(message.id(), message.conversationId(), message.senderSubject(),
                message.clientMessageId(), message.body(), message.createdAt());
    }

    record MessageView(UUID id, UUID conversationId, String senderSubject, UUID clientMessageId, String body,
                       Instant createdAt) {}

    record MessagePage(List<MessageView> messages, UUID nextBefore) {}
}
