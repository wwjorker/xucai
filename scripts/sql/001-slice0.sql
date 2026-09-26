-- 切片0：创建 xucai_* 独立库和演示账号。
-- MySQL 8；演示账号 buyer_demo / merchant_demo，密码 XucaiDemo2026!。
SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS `xucai_auth` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS `xucai_user` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS `xucai_catalog` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS `xucai_trade` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS `xucai_ai` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 初始化 role 表结构。
CREATE TABLE IF NOT EXISTS `xucai_auth`.`role` (
  `id` bigint NOT NULL COMMENT '主键',
  `code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '角色代号，例如：admin',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '角色名称',
  `type` tinyint DEFAULT '1' COMMENT '角色类型：0-固定角色（不可选）1-自定义角色',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `creater` bigint NOT NULL DEFAULT '0' COMMENT '创建者id',
  `updater` bigint NOT NULL DEFAULT '0' COMMENT '更新者id',
  `dep_id` bigint NOT NULL DEFAULT '0' COMMENT '部门id',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除，默认0',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表';

-- 初始化 login_record 表结构。
CREATE TABLE IF NOT EXISTS `xucai_auth`.`login_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户id',
  `cell_phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '手机号码',
  `login_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
  `logout_time` datetime DEFAULT NULL COMMENT '登出时间',
  `login_date` date DEFAULT NULL COMMENT '登录日期',
  `duration` bigint DEFAULT '0' COMMENT '登录时长，单位是秒',
  `ipv4` varchar(15) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT 'ip地址',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录信息记录表';

-- 初始化 user 表结构。
CREATE TABLE IF NOT EXISTS `xucai_user`.`user` (
  `id` bigint NOT NULL COMMENT '主键',
  `username` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户名',
  `cell_phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '手机号',
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '密码',
  `type` tinyint NOT NULL DEFAULT '0' COMMENT '用户类型：1-员工, 2-客户，3-商家',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '账户状态：0-禁用 1-正常',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `creater` bigint DEFAULT NULL COMMENT '创建者id',
  `updater` bigint DEFAULT '0' COMMENT '更新者id',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `username_idx` (`username`) USING BTREE,
  UNIQUE KEY `cell_idx` (`cell_phone`,`type`) USING BTREE,
  KEY `type_idx` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户账户表';

-- 初始化 user_detail 表结构。
CREATE TABLE IF NOT EXISTS `xucai_user`.`user_detail` (
  `id` bigint NOT NULL COMMENT '关联用户id',
  `type` tinyint DEFAULT '2' COMMENT '用户类型：1-员工, 2-客户，3-商家',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT '' COMMENT '名字',
  `gender` tinyint NOT NULL DEFAULT '0' COMMENT '性别：0-男性，1-女性',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '头像地址',
  `email` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '邮箱',
  `birthday` date DEFAULT NULL COMMENT '生日',
  `province` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '省',
  `city` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '市',
  `district` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '区',
  `photo` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '形象照地址',
  `role_id` bigint NOT NULL COMMENT '角色id',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `creater` bigint DEFAULT NULL COMMENT '创建者id',
  `updater` bigint DEFAULT '0' COMMENT '更新者id',
  `dep_id` bigint NOT NULL DEFAULT '0' COMMENT '部门id',
  PRIMARY KEY (`id`) USING BTREE,
  FULLTEXT KEY `name_idx` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户详情表';

-- 初始化 chat_session 表结构。
CREATE TABLE IF NOT EXISTS `xucai_ai`.`chat_session` (
  `id` bigint NOT NULL COMMENT '数据id',
  `session_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '会话id',
  `user_id` bigint NOT NULL DEFAULT '0' COMMENT '用户id',
  `title` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '会话标题',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `creater` bigint NOT NULL COMMENT '创建人',
  `updater` bigint NOT NULL COMMENT '更新人',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `session_id_index` (`session_id`) USING BTREE,
  KEY `user_id_index` (`user_id`) USING BTREE,
  KEY `update_time_index` (`update_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='对话session';

START TRANSACTION;
INSERT INTO xucai_auth.role (id,code,name,type) SELECT 1,'MERCHANT','演示商家',1 WHERE NOT EXISTS (SELECT 1 FROM xucai_auth.role WHERE id=1);
INSERT INTO xucai_auth.role (id,code,name,type) SELECT 2,'CUSTOMER','演示客户',1 WHERE NOT EXISTS (SELECT 1 FROM xucai_auth.role WHERE id=2);
INSERT INTO xucai_user.user (id,username,cell_phone,password,type,status) SELECT 10001,'buyer_demo','19900000001','$2a$12$WeoDcqFgJbFNFCzB9DY12.lIEZwp7U06kgirNyz5lXMdOJ2URxw1W',2,1 WHERE NOT EXISTS (SELECT 1 FROM xucai_user.user WHERE id=10001);
INSERT INTO xucai_user.user_detail (id,type,name,role_id) SELECT 10001,2,'演示客户',2 WHERE NOT EXISTS (SELECT 1 FROM xucai_user.user_detail WHERE id=10001);
INSERT INTO xucai_user.user (id,username,cell_phone,password,type,status) SELECT 10002,'merchant_demo','19900000002','$2a$12$WeoDcqFgJbFNFCzB9DY12.lIEZwp7U06kgirNyz5lXMdOJ2URxw1W',1,1 WHERE NOT EXISTS (SELECT 1 FROM xucai_user.user WHERE id=10002);
INSERT INTO xucai_user.user_detail (id,type,name,role_id) SELECT 10002,1,'演示商家',1 WHERE NOT EXISTS (SELECT 1 FROM xucai_user.user_detail WHERE id=10002);
COMMIT;
