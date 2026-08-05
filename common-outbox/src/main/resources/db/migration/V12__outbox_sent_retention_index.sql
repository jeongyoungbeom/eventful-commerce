DO $$
BEGIN
    IF to_regclass('public.outbox_event') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_outbox_event_sent_retention
            ON outbox_event (sent_at)
            WHERE status = 'SENT';
    END IF;
END $$;
