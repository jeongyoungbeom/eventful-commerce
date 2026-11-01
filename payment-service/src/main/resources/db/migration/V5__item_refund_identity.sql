ALTER TABLE payment_refund
    ADD COLUMN IF NOT EXISTS order_item_id UUID,
    ADD COLUMN IF NOT EXISTS sales_type VARCHAR(30) NOT NULL DEFAULT 'MARKETPLACE',
    ADD COLUMN IF NOT EXISTS request_content TEXT,
    ADD COLUMN IF NOT EXISTS refund_key UUID;
UPDATE payment_refund SET refund_key = COALESCE(order_item_id, seller_order_id) WHERE refund_key IS NULL;
ALTER TABLE payment_refund ALTER COLUMN refund_key SET NOT NULL;
DO $$ DECLARE old_key RECORD; BEGIN
    FOR old_key IN SELECT conname FROM pg_constraint
        WHERE conrelid = 'payment_refund'::regclass AND contype = 'u'
          AND pg_get_constraintdef(oid) = 'UNIQUE (payment_id, seller_order_id)'
    LOOP EXECUTE format('ALTER TABLE payment_refund DROP CONSTRAINT %I', old_key.conname); END LOOP;
END $$;
ALTER TABLE payment_refund ADD CONSTRAINT uk_payment_refund_target UNIQUE (payment_id, refund_key);
ALTER TABLE payment_refund ADD CONSTRAINT ck_payment_refund_scope CHECK (
    (sales_type = 'MARKETPLACE' AND order_item_id IS NULL AND refund_key = seller_order_id)
    OR (sales_type = 'DIRECT_PURCHASE' AND order_item_id IS NOT NULL AND refund_key = order_item_id)
);
