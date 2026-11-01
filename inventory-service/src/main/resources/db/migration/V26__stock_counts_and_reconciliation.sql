CREATE TABLE inventory_reconciliation (
    id UUID PRIMARY KEY, sku_id UUID NOT NULL REFERENCES inventory_sku(id),
    warehouse_id UUID REFERENCES inventory_warehouse(id), previous_report_id UUID REFERENCES inventory_reconciliation(id),
    checked_at TIMESTAMPTZ NOT NULL, actor_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    page_number INTEGER NOT NULL CHECK (page_number >= 0), page_size INTEGER NOT NULL CHECK (page_size BETWEEN 1 AND 200),
    has_next BOOLEAN NOT NULL, mismatch_count INTEGER NOT NULL CHECK (mismatch_count BETWEEN 0 AND page_size),
    request_content TEXT NOT NULL, result_content TEXT NOT NULL CHECK (jsonb_typeof(result_content::jsonb) = 'array'),
    CHECK (previous_report_id IS NULL OR previous_report_id <> id)
);
CREATE INDEX idx_inventory_reconciliation_scope ON inventory_reconciliation(sku_id,checked_at DESC,id);
CREATE TABLE inventory_stock_count (
    id UUID PRIMARY KEY, sku_id UUID NOT NULL, warehouse_id UUID NOT NULL REFERENCES inventory_warehouse(id), lot_id UUID NOT NULL,
    base_on_hand BIGINT NOT NULL CHECK (base_on_hand >= 0), base_blocked BIGINT NOT NULL CHECK (base_blocked IN (0,base_on_hand)),
    base_version BIGINT NOT NULL CHECK (base_version >= 0), started_at TIMESTAMPTZ NOT NULL, started_by UUID NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0), reconciliation_id UUID REFERENCES inventory_reconciliation(id),
    request_content TEXT NOT NULL, status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN','MEASURED','APPLIED','CANCELED')),
    counted_quantity BIGINT CHECK (counted_quantity >= 0), measured_at TIMESTAMPTZ, measured_by UUID,
    measurement_content TEXT, measurement_reason VARCHAR(500) CHECK (length(trim(measurement_reason)) > 0),
    adjustment BIGINT, applied_at TIMESTAMPTZ, applied_by UUID, approval_content TEXT,
    approval_reason VARCHAR(500) CHECK (length(trim(approval_reason)) > 0),
    canceled_at TIMESTAMPTZ, canceled_by UUID, cancellation_content TEXT,
    cancellation_reason VARCHAR(500) CHECK (length(trim(cancellation_reason)) > 0),
    FOREIGN KEY (lot_id,sku_id) REFERENCES inventory_lot(id,sku_id),
    FOREIGN KEY (warehouse_id,lot_id) REFERENCES inventory_stock_balance(warehouse_id,lot_id),
    CHECK ((counted_quantity IS NULL AND measured_at IS NULL AND measured_by IS NULL AND measurement_content IS NULL AND measurement_reason IS NULL)
        OR (counted_quantity IS NOT NULL AND measured_at IS NOT NULL AND measured_by IS NOT NULL AND measurement_content IS NOT NULL AND measurement_reason IS NOT NULL)),
    CHECK ((status='OPEN' AND counted_quantity IS NULL) OR (status IN ('MEASURED','APPLIED') AND counted_quantity IS NOT NULL) OR status='CANCELED'),
    CHECK ((status='APPLIED' AND applied_at IS NOT NULL AND applied_by IS NOT NULL AND approval_content IS NOT NULL
        AND approval_reason IS NOT NULL AND adjustment IS NOT NULL AND adjustment::numeric=counted_quantity::numeric-base_on_hand::numeric)
        OR (status<>'APPLIED' AND applied_at IS NULL AND applied_by IS NULL AND approval_content IS NULL AND approval_reason IS NULL AND adjustment IS NULL)),
    CHECK ((status='CANCELED' AND canceled_at IS NOT NULL AND canceled_by IS NOT NULL AND cancellation_content IS NOT NULL AND cancellation_reason IS NOT NULL)
        OR (status<>'CANCELED' AND canceled_at IS NULL AND canceled_by IS NULL AND cancellation_content IS NULL AND cancellation_reason IS NULL))
);
CREATE INDEX idx_inventory_count_operations ON inventory_stock_count(warehouse_id,started_at,id);
CREATE INDEX idx_inventory_count_report ON inventory_stock_count(reconciliation_id) WHERE reconciliation_id IS NOT NULL;
CREATE INDEX idx_inventory_reservation_operations ON inventory_reservation(warehouse_id,created_at,id)
    WHERE status IN ('PENDING','RESERVED','ALLOCATED');
CREATE INDEX idx_inventory_outbound_operations ON inventory_outbound(warehouse_id,created_at,id)
    WHERE status IN ('READY','PICKING','PACKED') OR (status='SHIPPED' AND delivered_at IS NULL);
CREATE INDEX idx_inventory_purchase_operations ON inventory_purchase_order(warehouse_id,created_at,id);
CREATE INDEX idx_inventory_receipt_operations ON inventory_receipt(confirmed_at,id);
