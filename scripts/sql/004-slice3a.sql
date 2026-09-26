-- 仅采购交易库；商家人工草稿，不开放核实或下单。
USE xucai_trade;
CREATE TABLE IF NOT EXISTS procurement_plan (
  id CHAR(36) PRIMARY KEY,
  request_hash CHAR(64) NOT NULL,
  requirement_id BIGINT NOT NULL,
  requirement_revision BIGINT NOT NULL,
  plan_no INT NOT NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'MERCHANT',
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  summary VARCHAR(500) NOT NULL,
  total_amount BIGINT NOT NULL,
  snapshot_json JSON NOT NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_plan_number(requirement_id,plan_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
