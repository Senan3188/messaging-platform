package com.example.messaging.messages;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "messages")
class Message {
    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "sender_subject", nullable = false, length = 255)
    private String senderSubject;

    @Column(name = "client_message_id", nullable = false)
    private UUID clientMessageId;

    @Column(nullable = false, length = 4000)
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Message() {}

    UUID id() {
        return id;
    }

    UUID conversationId() {
        return conversationId;
    }

    String senderSubject() {
        return senderSubject;
    }

    UUID clientMessageId() {
        return clientMessageId;
    }

    String body() {
        return body;
    }

    Instant createdAt() {
        return createdAt;
    }
}
