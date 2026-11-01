CREATE TABLE inventory_transfer (
    id UUID PRIMARY KEY,
    source_warehouse_id UUID NOT NULL REFERENCES inventory_warehouse(id),
    destination_warehouse_id UUID NOT NULL REFERENCES inventory_warehouse(id),
    expected_arrival_at TIMESTAMPTZ NOT NULL, actor_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0), request_content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('HELD','IN_TRANSIT','PARTIALLY_RECEIVED','RECEIVED','CANCELED')),
    dispatch_content TEXT, cancellation_content TEXT, created_at TIMESTAMPTZ NOT NULL,
    dispatched_at TIMESTAMPTZ, dispatched_by UUID, canceled_at TIMESTAMPTZ, canceled_by UUID,
    UNIQUE (id, source_warehouse_id),
    CHECK (source_warehouse_id <> destination_warehouse_id),
    CHECK (expected_arrival_at > created_at),
    CHECK ((status IN ('IN_TRANSIT','PARTIALLY_RECEIVED','RECEIVED')) = (dispatched_at IS NOT NULL)),
    CHECK ((dispatched_at IS NOT NULL) = (dispatch_content IS NOT NULL)),
    CHECK ((dispatched_at IS NOT NULL) = (dispatched_by IS NOT NULL)),
    CHECK ((status = 'CANCELED') = (canceled_at IS NOT NULL)),
    CHECK ((canceled_at IS NOT NULL) = (cancellation_content IS NOT NULL)),
    CHECK ((canceled_at IS NOT NULL) = (canceled_by IS NOT NULL))
);
CREATE INDEX idx_inventory_transfer_open ON inventory_transfer(expected_arrival_at, id)
    WHERE status IN ('HELD','IN_TRANSIT','PARTIALLY_RECEIVED');
CREATE TABLE inventory_transfer_line (
    id UUID PRIMARY KEY, transfer_id UUID NOT NULL, source_warehouse_id UUID NOT NULL,
    sku_id UUID NOT NULL, lot_id UUID NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity > 0), received_quantity BIGINT NOT NULL DEFAULT 0,
    CHECK (received_quantity BETWEEN 0 AND quantity), UNIQUE (transfer_id, lot_id),
    FOREIGN KEY (transfer_id, source_warehouse_id) REFERENCES inventory_transfer(id, source_warehouse_id),
    FOREIGN KEY (source_warehouse_id, lot_id, sku_id) REFERENCES inventory_stock_balance(warehouse_id, lot_id, sku_id)
);
CREATE TABLE inventory_transfer_receipt (
    id UUID PRIMARY KEY, transfer_id UUID NOT NULL REFERENCES inventory_transfer(id),
    actor_id UUID NOT NULL, reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    request_content TEXT NOT NULL, received_at TIMESTAMPTZ NOT NULL, UNIQUE (id, transfer_id)
);
CREATE INDEX idx_inventory_transfer_receipt ON inventory_transfer_receipt(transfer_id, received_at, id);
CREATE TABLE inventory_transfer_receipt_line (
    id UUID PRIMARY KEY, receipt_id UUID NOT NULL, transfer_id UUID NOT NULL, lot_id UUID NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity > 0), UNIQUE (receipt_id, lot_id),
    FOREIGN KEY (receipt_id, transfer_id) REFERENCES inventory_transfer_receipt(id, transfer_id),
    FOREIGN KEY (transfer_id, lot_id) REFERENCES inventory_transfer_line(transfer_id, lot_id)
);
