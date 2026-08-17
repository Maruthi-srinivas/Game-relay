ALTER TABLE rooms ADD COLUMN last_sequence BIGINT NOT NULL DEFAULT 0;

ALTER TABLE messages ADD COLUMN sequence_number BIGINT;

WITH numbered AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY room_id ORDER BY created_at ASC, id ASC) AS seq
    FROM messages
)
UPDATE messages m
SET sequence_number = numbered.seq
FROM numbered
WHERE m.id = numbered.id;

UPDATE rooms r
SET last_sequence = COALESCE((
    SELECT MAX(m.sequence_number) FROM messages m WHERE m.room_id = r.id
), 0);

ALTER TABLE messages ALTER COLUMN sequence_number SET NOT NULL;

CREATE UNIQUE INDEX idx_messages_room_sequence ON messages (room_id, sequence_number);
