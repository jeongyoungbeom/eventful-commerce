ALTER TABLE product_read_model ADD COLUMN sales_type VARCHAR(32) NOT NULL DEFAULT 'MARKETPLACE';
ALTER TABLE seller_orders ADD COLUMN sales_type VARCHAR(32) NOT NULL DEFAULT 'MARKETPLACE';
ALTER TABLE order_items ADD COLUMN delivery_fee_allocation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE product_read_model ADD CONSTRAINT ck_product_read_model_sales_identity CHECK (
    (sales_type = 'MARKETPLACE' AND seller_id <> '00000000-0000-0000-0000-000000000001') OR
    (sales_type = 'DIRECT_PURCHASE' AND seller_id = '00000000-0000-0000-0000-000000000001')
);
ALTER TABLE seller_orders ADD CONSTRAINT ck_seller_orders_sales_identity CHECK (
    (sales_type = 'MARKETPLACE' AND seller_id <> '00000000-0000-0000-0000-000000000001') OR
    (sales_type = 'DIRECT_PURCHASE' AND seller_id = '00000000-0000-0000-0000-000000000001')
);
ALTER TABLE order_items ADD CONSTRAINT ck_order_items_delivery_fee_allocation CHECK (delivery_fee_allocation >= 0);
