USE xucai_ai;
CREATE TABLE assistant_model_call (
 id VARCHAR(36) PRIMARY KEY,
 turn_id VARCHAR(36) NOT NULL,
 round_no INT NOT NULL,
 agent VARCHAR(24) NOT NULL,
 model VARCHAR(80) NOT NULL,
 status VARCHAR(16) NOT NULL,
 input_tokens INT NULL,
 output_tokens INT NULL,
 error_code VARCHAR(40) NULL,
 started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 finished_at DATETIME(3) NULL,
 UNIQUE KEY uk_assistant_call (turn_id,round_no),
 CONSTRAINT fk_assistant_call_turn FOREIGN KEY (turn_id) REFERENCES assistant_turn(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
