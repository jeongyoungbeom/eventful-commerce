CREATE INDEX idx_settlements_status_created_at_id
    ON settlements (status, created_at, id);
