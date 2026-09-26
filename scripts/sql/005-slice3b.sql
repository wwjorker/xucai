-- 仅采购隔离交易库；发布为新plan，不修改草稿内容。
USE xucai_trade;
CREATE TABLE IF NOT EXISTS procurement_plan_release (
 plan_id CHAR(36) PRIMARY KEY,
 draft_id CHAR(36) NOT NULL,
 review_json JSON NOT NULL,
 verified_by BIGINT NOT NULL,
 verified_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_release_draft(draft_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
