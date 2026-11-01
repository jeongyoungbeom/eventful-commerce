CREATE TABLE inventory_warehouse (
    id UUID PRIMARY KEY, code VARCHAR(40) NOT NULL UNIQUE CHECK (length(trim(code)) > 0),
    name VARCHAR(100) NOT NULL CHECK (length(trim(name)) > 0), address VARCHAR(300) NOT NULL CHECK (length(trim(address)) > 0),
    active BOOLEAN NOT NULL, accepts_reservations BOOLEAN NOT NULL, priority INTEGER NOT NULL CHECK (priority >= 0)
);
CREATE TABLE inventory_supplier (
    id UUID PRIMARY KEY, code VARCHAR(40) NOT NULL UNIQUE CHECK (length(trim(code)) > 0),
    name VARCHAR(100) NOT NULL CHECK (length(trim(name)) > 0)
);
CREATE TABLE inventory_sku (
    id UUID PRIMARY KEY, name VARCHAR(255) NOT NULL CHECK (length(trim(name)) > 0),
    active BOOLEAN NOT NULL, information_version BIGINT NOT NULL CHECK (information_version >= 0)
);
CREATE TABLE inventory_purchase_order (
    id UUID PRIMARY KEY, supplier_id UUID NOT NULL REFERENCES inventory_supplier(id),
    warehouse_id UUID NOT NULL REFERENCES inventory_warehouse(id), actor_id UUID NOT NULL,
    request_content TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE inventory_purchase_line (
    id UUID PRIMARY KEY, purchase_order_id UUID NOT NULL REFERENCES inventory_purchase_order(id),
    sku_id UUID NOT NULL REFERENCES inventory_sku(id), ordered_quantity BIGINT NOT NULL CHECK (ordered_quantity > 0),
    unit_cost BIGINT NOT NULL CHECK (unit_cost > 0), received_quantity BIGINT NOT NULL DEFAULT 0,
    CHECK (received_quantity >= 0 AND received_quantity <= ordered_quantity),
    CHECK (ordered_quantity::numeric * unit_cost <= 9223372036854775807),
    UNIQUE (purchase_order_id, sku_id)
);
CREATE TABLE inventory_lot (
    id UUID PRIMARY KEY, sku_id UUID NOT NULL REFERENCES inventory_sku(id),
    supplier_id UUID NOT NULL REFERENCES inventory_supplier(id), code VARCHAR(80) NOT NULL CHECK (length(trim(code)) > 0),
    expires_on DATE NOT NULL CHECK (expires_on BETWEEN DATE '1900-01-01' AND DATE '9999-12-31'),
    quality VARCHAR(20) NOT NULL CHECK (quality IN ('GOOD','DAMAGED','INSPECTION')),
    UNIQUE (sku_id, supplier_id, code, quality), UNIQUE (id, sku_id)
);
CREATE TABLE inventory_stock_balance (
    id UUID PRIMARY KEY, sku_id UUID NOT NULL, warehouse_id UUID NOT NULL REFERENCES inventory_warehouse(id),
    lot_id UUID NOT NULL, on_hand BIGINT NOT NULL, reserved BIGINT NOT NULL,
    allocated BIGINT NOT NULL, blocked BIGINT NOT NULL, transfer_held BIGINT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (lot_id, sku_id) REFERENCES inventory_lot(id, sku_id), UNIQUE (warehouse_id, lot_id),
    CHECK (on_hand >= 0 AND reserved >= 0 AND allocated >= 0 AND blocked >= 0 AND transfer_held >= 0),
    CHECK (reserved::numeric + allocated::numeric + blocked::numeric + transfer_held::numeric <= on_hand)
);
CREATE INDEX idx_inventory_stock_sku_warehouse ON inventory_stock_balance(sku_id, warehouse_id, lot_id);
CREATE TABLE inventory_receipt (
    id UUID PRIMARY KEY, purchase_order_id UUID NOT NULL REFERENCES inventory_purchase_order(id),
    actor_id UUID NOT NULL, reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    request_content TEXT NOT NULL, confirmed_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE inventory_receipt_line (
    id UUID PRIMARY KEY, receipt_id UUID NOT NULL REFERENCES inventory_receipt(id),
    purchase_line_id UUID NOT NULL REFERENCES inventory_purchase_line(id), lot_id UUID NOT NULL REFERENCES inventory_lot(id),
    quantity BIGINT NOT NULL CHECK (quantity > 0), unit_cost BIGINT NOT NULL CHECK (unit_cost > 0),
    purchase_amount BIGINT NOT NULL CHECK (purchase_amount > 0 AND purchase_amount = quantity::numeric * unit_cost),
    UNIQUE (receipt_id, purchase_line_id, lot_id)
);
CREATE TABLE inventory_inspection (
    id UUID PRIMARY KEY, receipt_line_id UUID NOT NULL UNIQUE REFERENCES inventory_receipt_line(id),
    good_quantity BIGINT NOT NULL CHECK (good_quantity >= 0), damaged_quantity BIGINT NOT NULL CHECK (damaged_quantity >= 0),
    actor_id UUID NOT NULL, reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0), request_content TEXT NOT NULL,
    inspected_at TIMESTAMPTZ NOT NULL,
    CHECK (good_quantity::numeric + damaged_quantity::numeric BETWEEN 1 AND 9223372036854775807)
);
CREATE TABLE inventory_stock_ledger (
    id UUID PRIMARY KEY, receipt_id UUID NOT NULL REFERENCES inventory_receipt(id),
    receipt_line_id UUID NOT NULL REFERENCES inventory_receipt_line(id),
    sku_id UUID NOT NULL, warehouse_id UUID NOT NULL REFERENCES inventory_warehouse(id), lot_id UUID NOT NULL,
    document_type VARCHAR(20) NOT NULL CHECK (document_type IN ('INBOUND','INSPECTION')), document_id UUID NOT NULL,
    transfer_held_delta BIGINT NOT NULL DEFAULT 0,
    on_hand_delta BIGINT NOT NULL CHECK (on_hand_delta <> 0), blocked_delta BIGINT NOT NULL,
    actor_id UUID NOT NULL, reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0), occurred_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (lot_id, sku_id) REFERENCES inventory_lot(id, sku_id),
    UNIQUE (document_type, document_id, lot_id),
    CHECK ((on_hand_delta > 0 AND blocked_delta BETWEEN 0 AND on_hand_delta)
        OR (on_hand_delta < 0 AND blocked_delta BETWEEN on_hand_delta AND 0))
);
CREATE INDEX idx_inventory_ledger_sku_time ON inventory_stock_ledger(sku_id, occurred_at, id);
CREATE INDEX idx_inventory_receipt_lines ON inventory_receipt_line(receipt_id, id);

-- 공통 모듈의 증분 V10~V21이 적용되기 전 빈 신규 DB의 기준선을 만든다.
CREATE TABLE outbox_event (
    id UUID PRIMARY KEY, aggregate_type VARCHAR(255) NOT NULL, aggregate_id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL, payload TEXT NOT NULL, status VARCHAR(255) NOT NULL,
    retry_count INTEGER NOT NULL, last_error TEXT, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processing_token UUID, processing_started_at TIMESTAMPTZ, failed_at TIMESTAMPTZ,
    requeue_count INTEGER NOT NULL DEFAULT 0, last_requeued_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL, sent_at TIMESTAMPTZ
);
CREATE TABLE processed_event (event_id UUID PRIMARY KEY, processed_at TIMESTAMPTZ NOT NULL);
