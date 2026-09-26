-- 只在采购隔离库新增表；保留已有数据。模型未启用时仍可保存人工需求。
USE xucai_trade;
CREATE TABLE IF NOT EXISTS procurement_requirement (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  title VARCHAR(100) NOT NULL,
  raw_text TEXT NOT NULL,
  structured_json JSON NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_requirement_owner (user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

USE xucai_ai;
CREATE TABLE IF NOT EXISTS ai_run (
  id CHAR(36) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  requirement_id BIGINT NOT NULL,
  requirement_revision BIGINT NOT NULL,
  input_json JSON NOT NULL,
  result_json JSON NULL,
  kind VARCHAR(16) NOT NULL DEFAULT 'ANALYZE',
  status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
  model VARCHAR(80) NOT NULL,
  prompt_version VARCHAR(40) NOT NULL,
  error_code VARCHAR(40) NULL,
  error_message VARCHAR(300) NULL,
  started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  active_owner BIGINT GENERATED ALWAYS AS (CASE WHEN status='RUNNING' THEN user_id ELSE NULL END) STORED,
  UNIQUE KEY uk_run_active_owner (active_owner),
  KEY idx_run_requirement (user_id,requirement_id,started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
