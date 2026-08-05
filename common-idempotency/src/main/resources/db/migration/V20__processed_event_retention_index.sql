DO $$
BEGIN
    IF to_regclass('public.processed_event') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_processed_event_processed_at
            ON processed_event (processed_at);
    END IF;
END $$;
