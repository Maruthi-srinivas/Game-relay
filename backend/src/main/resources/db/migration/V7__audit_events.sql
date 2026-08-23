CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_events_topic ON audit_events (topic);
CREATE INDEX idx_audit_events_created_at ON audit_events (created_at DESC);
