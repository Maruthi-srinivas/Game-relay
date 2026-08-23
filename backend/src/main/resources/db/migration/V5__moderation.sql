ALTER TABLE room_members ADD COLUMN muted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE messages ADD COLUMN edited_at TIMESTAMPTZ;

CREATE TABLE reports (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES rooms (id),
    reporter_id UUID NOT NULL REFERENCES users (id),
    target_user_id UUID REFERENCES users (id),
    message_id UUID REFERENCES messages (id),
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reports_room_id ON reports (room_id);
