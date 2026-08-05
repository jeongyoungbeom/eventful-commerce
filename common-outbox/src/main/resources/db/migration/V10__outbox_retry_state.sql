DO $$
BEGIN
    IF to_regclass('public.outbox_event') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE outbox_event
        ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP WITH TIME ZONE;

    UPDATE outbox_event
    SET next_attempt_at = COALESCE(next_attempt_at, created_at, CURRENT_TIMESTAMP)
    WHERE next_attempt_at IS NULL;

    ALTER TABLE outbox_event
        ALTER COLUMN next_attempt_at SET DEFAULT CURRENT_TIMESTAMP,
        ALTER COLUMN next_attempt_at SET NOT NULL,
        ADD COLUMN IF NOT EXISTS processing_token UUID,
        ADD COLUMN IF NOT EXISTS processing_started_at TIMESTAMP WITH TIME ZONE,
        ADD COLUMN IF NOT EXISTS failed_at TIMESTAMP WITH TIME ZONE;

    CREATE INDEX IF NOT EXISTS idx_outbox_event_publishable
        ON outbox_event (status, next_attempt_at, created_at);

    CREATE INDEX IF NOT EXISTS idx_outbox_event_processing_lease
        ON outbox_event (status, processing_started_at);
END $$;
