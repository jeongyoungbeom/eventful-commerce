ALTER TABLE shipping
    ADD COLUMN cancellation_request_id UUID,
    ADD COLUMN cancellation_approved BOOLEAN,
    ADD CONSTRAINT uk_shipping_cancellation_request UNIQUE (cancellation_request_id),
    ADD CONSTRAINT ck_shipping_cancellation_decision CHECK (
        (cancellation_request_id IS NULL AND cancellation_approved IS NULL)
        OR (cancellation_request_id IS NOT NULL AND cancellation_approved IS NOT NULL
            AND ((cancellation_approved AND status = 'CANCELED')
                 OR (NOT cancellation_approved AND status IN ('STARTED', 'COMPLETED', 'FAILED'))))
    );
CREATE INDEX idx_shipping_preparation ON shipping (status, created_at, id);
