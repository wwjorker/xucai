-- 执行一次；迁移工具必须先检查 expires_at 是否存在，禁止重复刷新期限。
USE xucai_trade;
ALTER TABLE purchase_order
 ADD COLUMN expires_at DATETIME(3) NULL,
 ADD COLUMN pay_channel VARCHAR(24) NULL,
 ADD COLUMN paid_at DATETIME(3) NULL,
 ADD COLUMN closed_at DATETIME(3) NULL,
 ADD COLUMN close_reason VARCHAR(24) NULL,
 ADD INDEX idx_purchase_expiry(status,expires_at);
-- 旧演示订单从迁移时开始获得30分钟宽限，不更改创建时间和原快照。
UPDATE purchase_order SET expires_at=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 MINUTE) WHERE expires_at IS NULL;
ALTER TABLE purchase_order MODIFY expires_at DATETIME(3) NOT NULL;
