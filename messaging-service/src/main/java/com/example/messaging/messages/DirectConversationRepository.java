package com.example.messaging.messages;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

interface DirectConversationRepository extends Repository<DirectConversation, UUID> {
    Optional<DirectConversation> findByParticipantAAndParticipantB(String first, String second);

    @Query("""
            select c from DirectConversation c
            where c.id = :id and (c.participantA = :subject or c.participantB = :subject)
            """)
    Optional<DirectConversation> findForParticipant(UUID id, String subject);

    @Query("""
            select c from DirectConversation c
            where c.participantA = :subject or c.participantB = :subject
            order by c.createdAt desc, c.id desc
            """)
    List<DirectConversation> findForParticipant(String subject, Pageable page);

    @Modifying
    @Transactional
    @Query(value = """
            insert into direct_conversations (id, participant_a, participant_b)
            values (:id, :first, :second)
            on conflict (participant_a, participant_b) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(UUID id, String first, String second);
}
