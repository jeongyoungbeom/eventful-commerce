ALTER TABLE seller_orders
    ADD COLUMN cancellation_status VARCHAR(32) NOT NULL DEFAULT 'NONE',
    ADD COLUMN cancellation_request_id UUID,
    ADD COLUMN cancellation_reason VARCHAR(255),
    ADD COLUMN cancellation_requested_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT uk_seller_orders_cancellation_request UNIQUE (cancellation_request_id),
    ADD CONSTRAINT ck_seller_orders_cancellation_request CHECK (
        (cancellation_status = 'NONE' AND cancellation_request_id IS NULL
            AND cancellation_reason IS NULL AND cancellation_requested_at IS NULL)
        OR (cancellation_status IN ('PENDING', 'APPROVED', 'REJECTED')
            AND cancellation_request_id IS NOT NULL AND cancellation_reason IS NOT NULL
            AND cancellation_requested_at IS NOT NULL)
    );
CREATE INDEX idx_seller_orders_pending_cancellation
    ON seller_orders (cancellation_requested_at, order_id) WHERE cancellation_status = 'PENDING';
