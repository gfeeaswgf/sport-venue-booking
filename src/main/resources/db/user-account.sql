-- 仅供全新 hmdp 数据库初始化账号基础表：先选中 hmdp，再执行本文件和 sport-booking.sql。
-- 若使用其他库名，请同时修改 sport-booking.sql 开头 USE 和后端连接配置，避免切换到不同数据库。
-- 已有 tb_user/tb_user_info 的用户无需执行；不删除表、不覆盖用户、不导入点评示例数据。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS tb_user (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  phone VARCHAR(11) NOT NULL,
  nick_name VARCHAR(32) DEFAULT '',
  icon VARCHAR(255) DEFAULT '',
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uniq_user_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_user_info (
  user_id BIGINT UNSIGNED NOT NULL,
  real_name VARCHAR(32) NULL,
  student_no VARCHAR(32) NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
