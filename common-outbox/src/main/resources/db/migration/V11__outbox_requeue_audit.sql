DO $$
BEGIN
    IF to_regclass('public.outbox_event') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE outbox_event
        ADD COLUMN IF NOT EXISTS requeue_count INTEGER,
        ADD COLUMN IF NOT EXISTS last_requeued_at TIMESTAMP WITH TIME ZONE;

    UPDATE outbox_event
    SET requeue_count = 0
    WHERE requeue_count IS NULL;

    ALTER TABLE outbox_event
        ALTER COLUMN requeue_count SET DEFAULT 0,
        ALTER COLUMN requeue_count SET NOT NULL;
END $$;
