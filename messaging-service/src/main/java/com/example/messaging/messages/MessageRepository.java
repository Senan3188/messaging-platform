package com.example.messaging.messages;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

interface MessageRepository extends Repository<Message, UUID> {
    Optional<Message> findByConversationIdAndSenderSubjectAndClientMessageId(
            UUID conversationId, String sender, UUID clientMessageId);

    Optional<Message> findByIdAndConversationId(UUID id, UUID conversationId);

    List<Message> findByConversationIdOrderByCreatedAtDescIdDesc(UUID conversationId, Pageable page);

    @Query(value = """
            select * from messages
            where conversation_id = :conversationId and (created_at, id) < (:createdAt, :id)
            order by created_at desc, id desc
            limit :limit
            """, nativeQuery = true)
    List<Message> findBefore(UUID conversationId, Instant createdAt, UUID id, int limit);

    @Modifying
    @Query(value = """
            insert into messages (id, conversation_id, sender_subject, client_message_id, body)
            values (:id, :conversationId, :sender, :clientMessageId, :body)
            on conflict (conversation_id, sender_subject, client_message_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(UUID id, UUID conversationId, String sender, UUID clientMessageId, String body);
}
