CREATE TABLE IF NOT EXISTS order_saga (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    status VARCHAR(64) NOT NULL,
    expected_refund_count INTEGER NOT NULL DEFAULT 0,
    refunded_count INTEGER NOT NULL DEFAULT 0,
    compensation_attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_order_saga_order UNIQUE (order_id)
);

CREATE INDEX IF NOT EXISTS idx_order_saga_status ON order_saga (status, updated_at);
