-- Persistent procurement conversations and messages.
USE xucai_ai;
CREATE TABLE assistant_session (
 id VARCHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 title VARCHAR(80) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 archived BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX ix_assistant_owner (user_id,archived,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE assistant_turn (
 id VARCHAR(36) PRIMARY KEY,
 session_id VARCHAR(36) NOT NULL,
 user_id BIGINT NOT NULL,
 ordinal BIGINT NOT NULL,
 request_text TEXT NOT NULL,
 status VARCHAR(16) NOT NULL,
 assistant_text TEXT NULL,
 result_json JSON NULL,
 error_code VARCHAR(40) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 finished_at DATETIME(3) NULL,
 UNIQUE KEY uk_assistant_turn_order (session_id,ordinal),
 INDEX ix_assistant_turn_owner (user_id,session_id),
 CONSTRAINT fk_assistant_turn_session FOREIGN KEY (session_id) REFERENCES assistant_session(id),
 CONSTRAINT ck_assistant_turn_status CHECK (status IN ('RUNNING','DONE','FAILED','CANCELED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
