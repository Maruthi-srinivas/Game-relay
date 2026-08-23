CREATE TABLE room_bans (
    room_id UUID NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    banned_by UUID NOT NULL REFERENCES users (id),
    reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);

CREATE INDEX idx_room_bans_user_id ON room_bans (user_id);

ALTER TABLE reports ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'OPEN';
ALTER TABLE reports ADD COLUMN resolved_at TIMESTAMPTZ;
ALTER TABLE reports ADD COLUMN resolver_id UUID REFERENCES users (id);

CREATE TABLE room_read_cursors (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    room_id UUID NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    last_read_sequence BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, room_id)
);

ALTER TABLE messages ADD COLUMN request_id VARCHAR(64);
CREATE UNIQUE INDEX idx_messages_request_id
    ON messages (room_id, sender_id, request_id)
    WHERE request_id IS NOT NULL;

ALTER TABLE messages ALTER COLUMN content DROP NOT NULL;
ALTER TABLE messages ALTER COLUMN content SET DEFAULT '';

CREATE TABLE message_reactions (
    message_id UUID NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    emoji VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, user_id, emoji)
);

CREATE INDEX idx_message_reactions_message_id ON message_reactions (message_id);

CREATE TABLE message_attachments (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_message_attachments_message_id ON message_attachments (message_id);

ALTER TABLE messages ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED;

CREATE INDEX idx_messages_content_tsv ON messages USING GIN (content_tsv);
