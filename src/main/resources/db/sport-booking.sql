-- 体育场馆预约：请在 Navicat 中选择原 hmdp 数据库后完整运行。
-- 仅增加表/字段，不删除或清空旧数据；可重复执行，不覆盖已预约名额。
-- 兼容 MySQL 5.6；导入前建议备份数据库。连接编码使用 UTF-8。
USE hmdp;
SET NAMES utf8mb4;
SET time_zone = '+08:00';

-- 复用原用户资料表。通过 information_schema 让新增字段支持重复执行。
SET @booking_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_user_info' AND COLUMN_NAME = 'real_name') = 0,
  'ALTER TABLE tb_user_info ADD COLUMN real_name VARCHAR(32) NULL COMMENT ''预约人真实姓名''', 'SELECT 1');
PREPARE booking_stmt FROM @booking_ddl;
EXECUTE booking_stmt;
DEALLOCATE PREPARE booking_stmt;
SET @booking_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_user_info' AND COLUMN_NAME = 'student_no') = 0,
  'ALTER TABLE tb_user_info ADD COLUMN student_no VARCHAR(32) NULL COMMENT ''学号或工号''', 'SELECT 1');
PREPARE booking_stmt FROM @booking_ddl;
EXECUTE booking_stmt;
DEALLOCATE PREPARE booking_stmt;

CREATE TABLE IF NOT EXISTS tb_venue (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 name VARCHAR(64) NOT NULL, category VARCHAR(32) NOT NULL,
 address VARCHAR(255) NOT NULL, description TEXT NULL, notice TEXT NULL,
 open_hours VARCHAR(64) NOT NULL, enabled TINYINT NOT NULL DEFAULT 1,
 cancel_advance_minutes INT NOT NULL DEFAULT 60,
 create_time DATETIME NOT NULL,
 PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_venue_court (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, venue_id BIGINT UNSIGNED NOT NULL,
 name VARCHAR(64) NOT NULL, mode VARCHAR(16) NOT NULL COMMENT 'COURT包场 SESSION个人名额',
 capacity INT NOT NULL DEFAULT 1, max_people INT NOT NULL DEFAULT 6,
 enabled TINYINT NOT NULL DEFAULT 1, create_time DATETIME NOT NULL,
 PRIMARY KEY (id), KEY idx_court_venue (venue_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_venue_slot (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, court_id BIGINT UNSIGNED NOT NULL,
 booking_date DATE NOT NULL, start_time TIME NOT NULL, end_time TIME NOT NULL,
 capacity INT NOT NULL, booked_count INT NOT NULL DEFAULT 0,
 enabled TINYINT NOT NULL DEFAULT 1, create_time DATETIME NOT NULL,
 PRIMARY KEY (id), UNIQUE KEY uk_slot_time (court_id, booking_date, start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_booking_order (
 id VARCHAR(32) NOT NULL, user_id BIGINT UNSIGNED NOT NULL,
 venue_id BIGINT UNSIGNED NOT NULL, court_id BIGINT UNSIGNED NOT NULL, slot_id BIGINT UNSIGNED NOT NULL,
 venue_name VARCHAR(64) NOT NULL, court_name VARCHAR(64) NOT NULL,
 booking_date DATE NOT NULL, active_date DATE NULL COMMENT '取消时置NULL；非取消订单占用每日一次资格',
 start_time TIME NOT NULL, end_time TIME NOT NULL,
 real_name VARCHAR(32) NOT NULL, student_no VARCHAR(32) NOT NULL, phone VARCHAR(32) NOT NULL,
 companions_json TEXT NOT NULL, people_count INT NOT NULL,
 status VARCHAR(16) NOT NULL COMMENT 'RESERVED待使用 COMPLETED已核销 CANCELLED已取消；过期由时间计算',
 verify_code VARCHAR(12) NOT NULL, cancel_advance_minutes INT NOT NULL,
 create_time DATETIME NOT NULL, cancel_time DATETIME NULL, verify_time DATETIME NULL,
 PRIMARY KEY (id), UNIQUE KEY uk_user_active_date (user_id, active_date), UNIQUE KEY uk_verify_code (verify_code),
 KEY idx_order_slot (slot_id), KEY idx_order_venue (venue_id, status, booking_date), KEY idx_order_user_time (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 以下为可保留/自行修改的演示场馆，不包含截图中的个人信息。
START TRANSACTION;
INSERT IGNORE INTO tb_venue (id,name,category,address,description,notice,open_hours,enabled,cancel_advance_minutes,create_time) VALUES
(1001,'综合体育馆 · 羽毛球','BADMINTON','南湖校区 · 综合体育馆二层','轻盈起跳，尽情挥拍。室内标准羽毛球场，适合双打、练习和朋友相约。','包场预约，每单含本人最多6人。请穿运动鞋入场，自备球拍。每人每天限预约一次，开场前至少60分钟可取消。请在开场前30分钟至结束前到服务台出示凭证核销。','08:00—21:00',1,60,NOW()),
(1002,'南湖网球场','TENNIS','南湖校区 · 室外运动中心','在阳光下舒展身体。标准室外网球场，开放时段以发布场次为准。','每场为包场预约，含本人最多6人。请自备运动装备，遇恶劣天气及时联系场馆。取消需至少提前60分钟，入场须出示预约凭证。','08:00—21:00',1,60,NOW()),
(1003,'校园游泳馆','SWIMMING','南湖校区 · 游泳中心','给生活留一点自在的蓝。按场次预约个人入场名额。','每单仅限本人入场，每场容量以预约页为准。请自备泳衣、泳帽，遵守救生员指引。开场前可取消，开场前30分钟至结束前可核销，结束后未核销自动显示已过期。','12:00—21:00',1,0,NOW()),
(1004,'体育中心 · 台球','BILLIARDS','南湖校区 · 体育中心一层','专注下一杆。与朋友一起享受轻松的运动时光。','按球台包场，每单含本人最多6人。文明使用器材，准时离场。开场前至少60分钟可取消。','08:00—21:00',1,60,NOW());
INSERT IGNORE INTO tb_venue_court (id,venue_id,name,mode,capacity,max_people,enabled,create_time) VALUES
(1101,1001,'1号羽毛球场','COURT',1,6,1,NOW()),
(1102,1001,'2号羽毛球场','COURT',1,6,1,NOW()),
(1103,1001,'3号羽毛球场','COURT',1,6,1,NOW()),
(1104,1001,'4号羽毛球场','COURT',1,6,1,NOW()),
(1105,1001,'5号羽毛球场','COURT',1,6,1,NOW()),
(1106,1001,'6号羽毛球场','COURT',1,6,1,NOW()),
(1107,1001,'7号羽毛球场','COURT',1,6,1,NOW()),
(1108,1001,'8号羽毛球场','COURT',1,6,1,NOW()),
(1109,1001,'9号羽毛球场','COURT',1,6,1,NOW()),
(1110,1001,'10号羽毛球场','COURT',1,6,1,NOW()),
(1111,1001,'11号羽毛球场','COURT',1,6,1,NOW()),
(1112,1001,'12号羽毛球场','COURT',1,6,1,NOW()),
(1201,1002,'1号网球场','COURT',1,6,1,NOW()),
(1202,1002,'2号网球场','COURT',1,6,1,NOW()),
(1203,1002,'3号网球场','COURT',1,6,1,NOW()),
(1204,1002,'4号网球场','COURT',1,6,1,NOW()),
(1301,1003,'1号个人入场','SESSION',350,1,1,NOW()),
(1401,1004,'1号台球桌','COURT',1,6,1,NOW()),
(1402,1004,'2号台球桌','COURT',1,6,1,NOW()),
(1403,1004,'3号台球桌','COURT',1,6,1,NOW()),
(1404,1004,'4号台球桌','COURT',1,6,1,NOW());

-- 补齐未来七天的演示时段：整数秒转换为 TIME，避免字符集冲突。
-- 通过 LEFT JOIN 排除已存在或重叠时段，保留库存和关闭状态。
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

-- 查看已登录账号的ID，用于启动参数 --booking.admin-user-ids=实际ID
-- SELECT id, phone, nick_name FROM tb_user;
