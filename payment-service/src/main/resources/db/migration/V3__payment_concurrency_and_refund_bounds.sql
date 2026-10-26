ALTER TABLE payment
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE payment
    ADD CONSTRAINT ck_payment_refund_bounds
        CHECK (amount > 0 AND refunded_amount >= 0 AND refunded_amount <= amount);

ALTER TABLE payment_refund
    ADD CONSTRAINT ck_payment_refund_positive CHECK (amount > 0);
