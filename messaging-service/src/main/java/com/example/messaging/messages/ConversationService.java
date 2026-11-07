package com.example.messaging.messages;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class ConversationService {
    private final DirectConversationRepository conversations;
    private final UserDirectory users;

    ConversationService(DirectConversationRepository conversations, UserDirectory users) {
        this.conversations = conversations;
        this.users = users;
    }

    ConversationView open(String subject, String peer, String accessToken) {
        if (subject.equals(peer)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recipient must be another user");
        }

        String first = subject.compareTo(peer) < 0 ? subject : peer;
        String second = first.equals(subject) ? peer : subject;
        return conversations.findByParticipantAAndParticipantB(first, second)
                .map(conversation -> view(conversation, subject))
                .orElseGet(() -> {
                    users.requireProfile(peer, accessToken);
                    conversations.insertIfAbsent(UUID.randomUUID(), first, second);
                    return view(conversations.findByParticipantAAndParticipantB(first, second).orElseThrow(), subject);
                });
    }

    List<ConversationView> list(String subject, int page, int limit) {
        return conversations.findForParticipant(subject, PageRequest.of(page, limit)).stream()
                .map(conversation -> view(conversation, subject)).toList();
    }

    void requireParticipant(UUID id, String subject) {
        conversations.findForParticipant(id, subject)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
    }

    private ConversationView view(DirectConversation conversation, String subject) {
        return new ConversationView(conversation.id(), conversation.peerOf(subject), conversation.createdAt());
    }

    record ConversationView(UUID id, String recipientSubject, Instant createdAt) {}
}
