-- 仅采购AI库。已发生的1次连通性探测计入首轮30次限额；重复执行不重置额度。
USE xucai_ai;
CREATE TABLE IF NOT EXISTS ai_model_budget (
 id VARCHAR(40) PRIMARY KEY,
 max_calls INT NOT NULL,
 used_calls INT NOT NULL,
 max_micro_yuan BIGINT NOT NULL,
 reserved_micro_yuan BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO ai_model_budget VALUES ('requirement-pilot-v1',30,1,10000000,18);
CREATE TABLE IF NOT EXISTS ai_model_call (
 run_id VARCHAR(36) PRIMARY KEY,
 requirement_id BIGINT NOT NULL,
 requirement_revision BIGINT NOT NULL,
 model VARCHAR(80) NOT NULL,
 state VARCHAR(24) NOT NULL,
 reserved_micro_yuan BIGINT NOT NULL,
 input_tokens INT NULL,
 output_tokens INT NULL,
 error_code VARCHAR(40) NULL,
 started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 finished_at DATETIME(3) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
