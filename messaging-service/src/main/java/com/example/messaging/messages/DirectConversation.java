package com.example.messaging.messages;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "direct_conversations")
class DirectConversation {
    @Id
    private UUID id;

    @Column(name = "participant_a", nullable = false, length = 255)
    private String participantA;

    @Column(name = "participant_b", nullable = false, length = 255)
    private String participantB;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DirectConversation() {}

    UUID id() {
        return id;
    }

    String peerOf(String subject) {
        return participantA.equals(subject) ? participantB : participantA;
    }

    Instant createdAt() {
        return createdAt;
    }
}
