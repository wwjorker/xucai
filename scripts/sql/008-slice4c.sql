-- 一次性迁移：先检查 shipment_json 是否已存在；不要重复执行。
USE xucai_trade;
ALTER TABLE purchase_order
 ADD COLUMN shipment_json JSON NULL,
 ADD COLUMN shipped_at DATETIME(3) NULL,
 ADD COLUMN completed_at DATETIME(3) NULL;
CREATE TABLE IF NOT EXISTS purchase_after_sale (
 id CHAR(36) NOT NULL PRIMARY KEY,
 order_id CHAR(36) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
 resolution VARCHAR(2000) NULL,
 reviewed_by BIGINT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 reviewed_at DATETIME(3) NULL,
 KEY idx_after_sale_order(order_id,created_at),
 KEY idx_after_sale_open(order_id,status)
);
