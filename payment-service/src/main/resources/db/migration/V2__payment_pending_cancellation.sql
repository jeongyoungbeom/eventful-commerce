ALTER TABLE payment
    ADD COLUMN IF NOT EXISTS cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS pending_cancellation_payload TEXT;
