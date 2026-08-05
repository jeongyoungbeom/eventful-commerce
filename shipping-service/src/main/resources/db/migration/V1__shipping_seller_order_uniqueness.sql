DO $$
BEGIN
    IF to_regclass('public.shipping') IS NULL THEN
        RETURN;
    END IF;

    IF EXISTS (SELECT 1 FROM shipping WHERE seller_order_id IS NULL) THEN
        RAISE EXCEPTION 'Cannot make seller_order_id mandatory: existing shipping rows contain NULL';
    END IF;

    IF EXISTS (SELECT 1 FROM shipping GROUP BY seller_order_id HAVING COUNT(*) > 1) THEN
        RAISE EXCEPTION 'Cannot add shipping uniqueness: duplicate seller_order_id rows exist';
    END IF;

    ALTER TABLE shipping ALTER COLUMN seller_order_id SET NOT NULL;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_shipping_seller_order') THEN
        ALTER TABLE shipping
            ADD CONSTRAINT uk_shipping_seller_order UNIQUE (seller_order_id);
    END IF;
END $$;
