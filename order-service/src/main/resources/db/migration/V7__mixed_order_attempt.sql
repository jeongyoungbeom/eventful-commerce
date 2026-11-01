CREATE TABLE mixed_order_attempt (
    id UUID PRIMARY KEY REFERENCES orders(id),
    items_json TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    delivery_fee BIGINT NOT NULL CHECK (delivery_fee >= 0),
    free_delivery_threshold BIGINT NOT NULL CHECK (free_delivery_threshold >= 0),
    commission_rate DOUBLE PRECISION NOT NULL CHECK (commission_rate >= 0 AND commission_rate <= 1),
    composition_version BIGINT NOT NULL DEFAULT 1 CHECK (composition_version > 0),
    confirmed_version BIGINT CHECK (confirmed_version = composition_version),
    next_recovery_at TIMESTAMPTZ,
    recovery_cursor INTEGER NOT NULL DEFAULT 0 CHECK (recovery_cursor >= 0),
    CONSTRAINT ck_mixed_items_array CHECK (jsonb_typeof(items_json::jsonb) = 'array' AND jsonb_array_length(items_json::jsonb) > 0)
);
CREATE INDEX idx_mixed_attempt_recovery ON mixed_order_attempt(next_recovery_at) WHERE next_recovery_at IS NOT NULL;
