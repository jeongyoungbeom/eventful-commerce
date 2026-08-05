CREATE TABLE IF NOT EXISTS dead_letter_event (
    id UUID PRIMARY KEY,
    original_topic VARCHAR(255) NOT NULL,
    original_partition INTEGER NOT NULL,
    original_offset BIGINT NOT NULL,
    record_key VARCHAR(255),
    payload TEXT NOT NULL,
    exception_class VARCHAR(255),
    exception_message TEXT,
    status VARCHAR(32) NOT NULL,
    replay_count INTEGER NOT NULL DEFAULT 0,
    last_replayed_at TIMESTAMP WITH TIME ZONE,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_dead_letter_event_origin UNIQUE (original_topic, original_partition, original_offset)
);

CREATE INDEX IF NOT EXISTS idx_dead_letter_event_pending
    ON dead_letter_event (status, received_at);
