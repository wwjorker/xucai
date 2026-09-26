-- 只在采购交易库建立确认订单；保留既有需求内容。
USE xucai_trade;
CREATE TABLE IF NOT EXISTS purchase_order (
 id CHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 requirement_id BIGINT NOT NULL,
 plan_id CHAR(36) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'NO_PAY',
 total_amount BIGINT NOT NULL,
 address_json JSON NOT NULL,
 snapshot_json JSON NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_purchase_plan(plan_id),
 UNIQUE KEY uk_purchase_requirement(requirement_id),
 KEY idx_purchase_owner(user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS purchase_order_item (
 order_id CHAR(36) NOT NULL,
 product_id BIGINT NOT NULL,
 quantity INT NOT NULL,
 unit_price BIGINT NOT NULL,
 PRIMARY KEY(order_id,product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
