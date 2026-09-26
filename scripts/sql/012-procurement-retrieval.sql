-- 仅采购AI库；IF NOT EXISTS/INSERT IGNORE保留已发生的调用与索引，不重置预算。
USE xucai_ai;
CREATE TABLE IF NOT EXISTS retrieval_budget (
 id VARCHAR(40) PRIMARY KEY,max_calls INT NOT NULL,used_calls INT NOT NULL,
 used_documents INT NOT NULL,used_queries INT NOT NULL,max_micro_yuan BIGINT NOT NULL,reserved_micro_yuan BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO retrieval_budget VALUES('embedding-pilot-v1',6,0,0,0,1000000,0);
CREATE TABLE IF NOT EXISTS retrieval_model_call (
 id VARCHAR(44) PRIMARY KEY,kind VARCHAR(12) NOT NULL,text_count INT NOT NULL,input_bytes INT NOT NULL,
 model VARCHAR(64) NOT NULL,state VARCHAR(16) NOT NULL,reserved_micro_yuan BIGINT NOT NULL,input_tokens INT,
 error_code VARCHAR(64),started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),finished_at DATETIME(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS retrieval_operation (
 id CHAR(36) PRIMARY KEY,created_by BIGINT NOT NULL,kind VARCHAR(12) NOT NULL,request_hash CHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL,result_json MEDIUMTEXT,error_code VARCHAR(64),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),finished_at DATETIME(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS retrieval_index (
 product_id BIGINT PRIMARY KEY,generation CHAR(36) NOT NULL UNIQUE,snapshot_hash CHAR(64) NOT NULL,
 model VARCHAR(64) NOT NULL,dimensions INT NOT NULL,chunk_count INT NOT NULL,snapshot_json MEDIUMTEXT NOT NULL,
 built_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
