ALTER TABLE rooms ADD COLUMN direct_key VARCHAR(80);

CREATE UNIQUE INDEX idx_rooms_direct_key ON rooms (direct_key) WHERE direct_key IS NOT NULL;

CREATE TABLE room_invites (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    code VARCHAR(32) NOT NULL UNIQUE,
    created_by UUID NOT NULL REFERENCES users (id),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_room_invites_room_id ON room_invites (room_id);

INSERT INTO users (id, username, email, password_hash, status)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'system',
    'system@gamechat.local',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    'SYSTEM'
);

INSERT INTO rooms (id, name, type, owner_id, max_members, last_sequence)
VALUES (
    '00000000-0000-0000-0000-000000000010',
    'Global Lobby',
    'GLOBAL',
    '00000000-0000-0000-0000-000000000001',
    1000,
    0
);
