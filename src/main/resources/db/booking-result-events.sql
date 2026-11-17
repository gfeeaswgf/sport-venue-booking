-- 方案 A 增量迁移。由用户在原 hmdp 库执行；先停止旧版后端，禁止新旧发布器混跑。
-- 最低要求 MySQL 5.6.4；执行前保存 SHOW CREATE TABLE tb_booking_request 的结果。
-- 本文件不是重复执行脚本：先核对列不存在，再执行 ALTER，不自动 DROP 任何旧数据。
USE hmdp;
SET NAMES utf8mb4;

ALTER TABLE tb_booking_request
    ADD COLUMN result_event_state VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PENDING/PUBLISHED/COMPLETED',
    ADD COLUMN result_publish_count INT NOT NULL DEFAULT 0,
    ADD COLUMN result_publish_token VARCHAR(32) NULL,
    ADD COLUMN result_last_publish_time DATETIME(3) NULL,
    ADD COLUMN audit_state VARCHAR(16) NOT NULL DEFAULT 'SKIPPED' COMMENT 'PENDING/DONE/EXPIRED/SKIPPED',
    ADD COLUMN stats_state VARCHAR(16) NOT NULL DEFAULT 'SKIPPED' COMMENT 'PENDING/DONE/EXPIRED/SKIPPED',
    ADD COLUMN audit_finish_time DATETIME(3) NULL,
    ADD COLUMN stats_finish_time DATETIME(3) NULL;

-- 只恢复旧版未完成的终态后处理，不重放已处理完毕的全部历史。
-- 先依据旧字段筛选，最后修改 next_publish_time；旧 Redis 效果由固定Key/COUNT+max幂等复用。
UPDATE tb_booking_request
SET result_event_state = 'PENDING',
    audit_state = 'PENDING',
    stats_state = CASE WHEN status = 'SUCCEEDED' THEN 'PENDING' ELSE 'SKIPPED' END,
    next_publish_time = DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)
WHERE status IN ('SUCCEEDED', 'FAILED')
  AND next_publish_time < '9999-01-01 00:00:00'
  AND result_event_state = 'NONE';

SHOW CREATE TABLE tb_booking_request;
SELECT status, result_event_state, audit_state, stats_state, COUNT(*) AS requests
FROM tb_booking_request
GROUP BY status, result_event_state, audit_state, stats_state;

-- 原索引 (status,next_publish_time) 优先复用；仅只读检查，不擅自增加索引。
EXPLAIN SELECT id FROM tb_booking_request
WHERE status = 'SUCCEEDED'
  AND next_publish_time <= DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)
  AND result_event_state IN ('PENDING', 'PUBLISHED')
ORDER BY next_publish_time,id LIMIT 32;
