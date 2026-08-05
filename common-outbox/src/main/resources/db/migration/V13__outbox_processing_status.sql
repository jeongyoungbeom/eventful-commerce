DO $$
BEGIN
    IF to_regclass('public.outbox_event') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE outbox_event
        DROP CONSTRAINT IF EXISTS outbox_event_status_check;

    ALTER TABLE outbox_event
        ADD CONSTRAINT outbox_event_status_check
        CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED'));
END $$;
