CREATE TABLE IF NOT EXISTS order_request_idempotency (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_json TEXT,
    order_id UUID,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE order_request_idempotency
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP WITH TIME ZONE;

UPDATE order_request_idempotency
SET expires_at = created_at + INTERVAL '1 day'
WHERE expires_at IS NULL;

ALTER TABLE order_request_idempotency
    ALTER COLUMN expires_at SET NOT NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM order_request_idempotency
        GROUP BY user_id, idempotency_key
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot add order idempotency uniqueness: duplicate (user_id, idempotency_key) rows exist';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_order_request_idempotency_user_key'
    ) THEN
        ALTER TABLE order_request_idempotency
            ADD CONSTRAINT uk_order_request_idempotency_user_key UNIQUE (user_id, idempotency_key);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_order_request_idempotency_expires_at
    ON order_request_idempotency (expires_at);

DO $$
BEGIN
    IF to_regclass('public.seller_orders') IS NOT NULL THEN
        IF EXISTS (
            SELECT 1 FROM seller_orders GROUP BY order_id, seller_id HAVING COUNT(*) > 1
        ) THEN
            RAISE EXCEPTION 'Cannot add seller order uniqueness: duplicate (order_id, seller_id) rows exist';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_seller_orders_order_seller') THEN
            ALTER TABLE seller_orders
                ADD CONSTRAINT uk_seller_orders_order_seller UNIQUE (order_id, seller_id);
        END IF;
    END IF;

    IF to_regclass('public.order_items') IS NOT NULL THEN
        IF EXISTS (
            SELECT 1 FROM order_items GROUP BY reservation_id HAVING COUNT(*) > 1
        ) THEN
            RAISE EXCEPTION 'Cannot add reservation uniqueness: duplicate reservation_id rows exist';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_order_items_reservation_id') THEN
            ALTER TABLE order_items
                ADD CONSTRAINT uk_order_items_reservation_id UNIQUE (reservation_id);
        END IF;
    END IF;
END $$;
