ALTER TABLE settlements ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE settlement_refund (
    refund_id UUID PRIMARY KEY,
    settlement_id UUID NOT NULL REFERENCES settlements(id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    reason TEXT NOT NULL,
    refunded_at VARCHAR(255) NOT NULL
);
