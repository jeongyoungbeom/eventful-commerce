ALTER TABLE orders ADD COLUMN IF NOT EXISTS mixed_order BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE mixed_order_attempt
    ADD COLUMN IF NOT EXISTS payment_state VARCHAR(20) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS payment_result_json TEXT;
UPDATE orders SET mixed_order = TRUE WHERE id IN (SELECT id FROM mixed_order_attempt);
ALTER TABLE mixed_order_attempt ADD CONSTRAINT ck_mixed_payment_state
    CHECK (payment_state IN ('NONE','READY','COMPLETED','FAILED'));
