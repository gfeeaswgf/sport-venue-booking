-- 方案B：只新增预约请求表，不改动/清空原订单、用户、Redis数据。
-- 在原hmdp数据库中由用户自行执行；支持MySQL5.6及以上。
USE hmdp;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS tb_booking_request (
    id VARCHAR(32) NOT NULL COMMENT '受理编号，成功后也是订单编号',
    user_id BIGINT UNSIGNED NOT NULL,
    idempotency_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    venue_id BIGINT UNSIGNED NOT NULL,
    court_id BIGINT UNSIGNED NOT NULL,
    slot_id BIGINT UNSIGNED NOT NULL,
    booking_date DATE NOT NULL,
    active_date DATE NULL COMMENT '待处理/成功占资格，失败或订单取消置NULL',
    real_name VARCHAR(32) NOT NULL,
    student_no VARCHAR(32) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    companions_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'ACCEPTED/SUCCEEDED/FAILED',
    failure_reason VARCHAR(255) NULL,
    publish_count INT NOT NULL DEFAULT 0,
    next_publish_time DATETIME NOT NULL,
    expire_time DATETIME NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    complete_time DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_request_idempotency (user_id, idempotency_key),
    UNIQUE KEY uk_request_active_date (user_id, active_date),
    KEY idx_request_dispatch (status, next_publish_time),
    KEY idx_request_slot (slot_id, status),
    KEY idx_request_user_time (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 只读核验：
SHOW CREATE TABLE tb_booking_request;
