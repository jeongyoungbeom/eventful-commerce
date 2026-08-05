ALTER TABLE order_saga
    ADD COLUMN IF NOT EXISTS reconciliation_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_reconciled_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE IF NOT EXISTS order_saga_refund_receipt (
    id UUID PRIMARY KEY,
    saga_id UUID NOT NULL,
    seller_order_id UUID NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_order_saga_refund_receipt UNIQUE (saga_id, seller_order_id),
    CONSTRAINT fk_order_saga_refund_receipt_saga
        FOREIGN KEY (saga_id) REFERENCES order_saga(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_order_saga_reconciliation
    ON order_saga (status, last_reconciled_at);
