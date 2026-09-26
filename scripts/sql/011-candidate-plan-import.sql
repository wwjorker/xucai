-- 独立映射表；不改旧方案行。与新DRAFT在同一交易库事务提交。
USE xucai_trade;
CREATE TABLE IF NOT EXISTS candidate_plan_import (
  run_id CHAR(36) PRIMARY KEY,
  plan_id CHAR(36) NOT NULL,
  requirement_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_candidate_plan(plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
