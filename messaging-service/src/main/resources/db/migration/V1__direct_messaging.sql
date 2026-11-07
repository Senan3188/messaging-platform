CREATE TABLE direct_conversations (
    id uuid PRIMARY KEY,
    participant_a varchar(255) COLLATE "C" NOT NULL,
    participant_b varchar(255) COLLATE "C" NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CHECK (participant_a <> participant_b),
    UNIQUE (participant_a, participant_b)
);

CREATE INDEX direct_conversations_participant_a_idx ON direct_conversations (participant_a, created_at DESC, id DESC);
CREATE INDEX direct_conversations_participant_b_idx ON direct_conversations (participant_b, created_at DESC, id DESC);

CREATE TABLE messages (
    id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES direct_conversations (id),
    sender_subject varchar(255) NOT NULL,
    client_message_id uuid NOT NULL,
    body varchar(4000) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    UNIQUE (conversation_id, sender_subject, client_message_id)
);

CREATE INDEX messages_history_idx ON messages (conversation_id, created_at DESC, id DESC);
