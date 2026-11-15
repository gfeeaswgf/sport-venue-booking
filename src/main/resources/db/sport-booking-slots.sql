-- 修复场次为空：在原 hmdp 数据库完整执行此文件即可，无需重新建表或重启后端。
-- 只补齐今天起七天内缺失的演示场次，不清空订单，不覆盖库存/关闭状态，不插入重叠时段。
-- 请自行在本地 SQL 工具中执行；本文件未由程序自动执行。
-- MySQL 5.6/5.7：以整数秒计算 TIME，避免字符集/排序规则不同导致时间比较失败。
USE hmdp;
SET NAMES utf8mb4;
SET time_zone = '+08:00';

START TRANSACTION;

INSERT IGNORE INTO tb_venue_slot
    (court_id, booking_date, start_time, end_time, capacity, booked_count, enabled, create_time)
SELECT c.id, DATE_ADD(CURDATE(), INTERVAL d.n DAY),
       SEC_TO_TIME(t.start_second), SEC_TO_TIME(t.end_second), c.capacity, 0, 1, NOW()
FROM tb_venue_court c
JOIN tb_venue v ON v.id = c.venue_id AND v.enabled = 1
CROSS JOIN (
    SELECT 0 AS n
    UNION ALL SELECT 1 AS n
    UNION ALL SELECT 2 AS n
    UNION ALL SELECT 3 AS n
    UNION ALL SELECT 4 AS n
    UNION ALL SELECT 5 AS n
    UNION ALL SELECT 6 AS n
) d
CROSS JOIN (
    SELECT 28800 AS start_second, 32400 AS end_second
    UNION ALL SELECT 32400 AS start_second, 36000 AS end_second
    UNION ALL SELECT 36000 AS start_second, 39600 AS end_second
    UNION ALL SELECT 39600 AS start_second, 43200 AS end_second
    UNION ALL SELECT 43200 AS start_second, 46800 AS end_second
    UNION ALL SELECT 46800 AS start_second, 50400 AS end_second
    UNION ALL SELECT 50400 AS start_second, 54000 AS end_second
    UNION ALL SELECT 54000 AS start_second, 57600 AS end_second
    UNION ALL SELECT 57600 AS start_second, 61200 AS end_second
    UNION ALL SELECT 61200 AS start_second, 64800 AS end_second
    UNION ALL SELECT 64800 AS start_second, 68400 AS end_second
    UNION ALL SELECT 68400 AS start_second, 72000 AS end_second
    UNION ALL SELECT 72000 AS start_second, 75600 AS end_second
) t
LEFT JOIN tb_venue_slot existing_slot
    ON existing_slot.court_id = c.id
    AND existing_slot.booking_date = DATE_ADD(CURDATE(), INTERVAL d.n DAY)
    AND existing_slot.start_time < SEC_TO_TIME(t.end_second)
    AND existing_slot.end_time > SEC_TO_TIME(t.start_second)
WHERE c.mode = 'COURT' AND c.enabled = 1
    AND c.venue_id IN (1001, 1002, 1003, 1004)
    AND existing_slot.id IS NULL;

INSERT IGNORE INTO tb_venue_slot
    (court_id, booking_date, start_time, end_time, capacity, booked_count, enabled, create_time)
SELECT c.id, DATE_ADD(CURDATE(), INTERVAL d.n DAY),
       SEC_TO_TIME(t.start_second), SEC_TO_TIME(t.end_second), c.capacity, 0, 1, NOW()
FROM tb_venue_court c
JOIN tb_venue v ON v.id = c.venue_id AND v.enabled = 1
CROSS JOIN (
    SELECT 0 AS n
    UNION ALL SELECT 1 AS n
    UNION ALL SELECT 2 AS n
    UNION ALL SELECT 3 AS n
    UNION ALL SELECT 4 AS n
    UNION ALL SELECT 5 AS n
    UNION ALL SELECT 6 AS n
) d
CROSS JOIN (
    SELECT 43200 AS start_second, 51000 AS end_second
    UNION ALL SELECT 52200 AS start_second, 58200 AS end_second
    UNION ALL SELECT 59400 AS start_second, 67200 AS end_second
    UNION ALL SELECT 68400 AS start_second, 75600 AS end_second
) t
LEFT JOIN tb_venue_slot existing_slot
    ON existing_slot.court_id = c.id
    AND existing_slot.booking_date = DATE_ADD(CURDATE(), INTERVAL d.n DAY)
    AND existing_slot.start_time < SEC_TO_TIME(t.end_second)
    AND existing_slot.end_time > SEC_TO_TIME(t.start_second)
WHERE c.mode = 'SESSION' AND c.enabled = 1
    AND c.venue_id IN (1001, 1002, 1003, 1004)
    AND existing_slot.id IS NULL;

COMMIT;

-- 执行后检查每个场馆是否有场次。初始21块场地全部开放且没有既有时段时，共1848条。
SELECT v.id AS venue_id, v.name AS venue_name, COUNT(s.id) AS slot_count,
       MIN(s.booking_date) AS first_date, MAX(s.booking_date) AS last_date
FROM tb_venue v
LEFT JOIN tb_venue_court c ON c.venue_id = v.id
LEFT JOIN tb_venue_slot s ON s.court_id = c.id
    AND s.booking_date BETWEEN CURDATE() AND DATE_ADD(CURDATE(), INTERVAL 6 DAY)
WHERE v.id IN (1001, 1002, 1003, 1004)
GROUP BY v.id, v.name ORDER BY v.id;

-- 具体可预约条件：场馆/场地/时段开放，名额未满，并且尚未开始。
-- 今天已经闭馆时请在页面选择明天或之后的日期。
