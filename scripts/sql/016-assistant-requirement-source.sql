USE xucai_trade;
CREATE TABLE requirement_assistant_source (
 user_id BIGINT NOT NULL,
 session_id VARCHAR(36) NOT NULL,
 turn_id VARCHAR(36) NOT NULL,
 requirement_id BIGINT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(user_id,session_id,turn_id),
 UNIQUE KEY uk_assistant_requirement(requirement_id),
 CONSTRAINT fk_assistant_requirement FOREIGN KEY(requirement_id) REFERENCES procurement_requirement(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
