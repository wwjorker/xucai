-- 在采购交易库单次执行。保留旧订单/方案/需求及所有历史快照。
-- MySQL DDL非整批事务；部署前核对结构与备份，失败不盲目重放。
USE xucai_trade;
ALTER TABLE purchase_order MODIFY requirement_id BIGINT NULL, MODIFY plan_id CHAR(36) NULL,
 ADD CONSTRAINT ck_purchase_origin CHECK ((requirement_id IS NULL AND plan_id IS NULL) OR (requirement_id IS NOT NULL AND plan_id IS NOT NULL));
CREATE TABLE procurement_cart (
 user_id BIGINT PRIMARY KEY,
 revision BIGINT NOT NULL DEFAULT 0,
 items_json JSON NOT NULL,
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE checkout_intent (
 id CHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 cart_revision BIGINT NOT NULL,
 address_json JSON NOT NULL,
 snapshot_json JSON NOT NULL,
 expires_at DATETIME(3) NOT NULL,
 order_id CHAR(36) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_checkout_order(order_id),
 KEY idx_checkout_owner(user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
