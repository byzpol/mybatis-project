-- ============================================================================
--  企业级 CMS 内容管理系统 · 数据库初始化脚本
--  项目：基于实验二 SSM 整合升级 · 企业级 CMS 内容管理系统（课程项目设计）
--  数据库：cms_db    字符集：utf8mb4 / utf8mb4_general_ci    引擎：InnoDB
--  版本：V1.0        编制：成员A        日期：2026-10-08
--
--  ★ 本脚本可重复执行：先按依赖倒序 DROP，再按正序 CREATE
--  ★ 使用逻辑外键（不用 FOREIGN KEY 物理约束），便于逻辑删除与数据维护
--  ★ 所有表统一带：id / create_by / create_time / update_by / update_time / deleted
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `cms_db`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE `cms_db`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================================
--  一、DROP（按依赖倒序，先删被引用的后删引用方）
-- ============================================================================
DROP TABLE IF EXISTS `sys_report`;
DROP TABLE IF EXISTS `cms_article_index`;
DROP TABLE IF EXISTS `sys_login_log`;
DROP TABLE IF EXISTS `sys_oper_log`;
DROP TABLE IF EXISTS `cms_article_version`;
DROP TABLE IF EXISTS `cms_article_audit_log`;
DROP TABLE IF EXISTS `cms_article_tag`;
DROP TABLE IF EXISTS `cms_article`;
DROP TABLE IF EXISTS `cms_tag`;
DROP TABLE IF EXISTS `cms_category_audit_log`;
DROP TABLE IF EXISTS `cms_category`;
DROP TABLE IF EXISTS `sys_apply_approval`;
DROP TABLE IF EXISTS `sys_role_permission`;
DROP TABLE IF EXISTS `sys_user_role`;
DROP TABLE IF EXISTS `sys_permission`;
DROP TABLE IF EXISTS `sys_role`;
DROP TABLE IF EXISTS `sys_user`;

-- ============================================================================
--  二、权限域（6 张表）  主责：成员C
-- ============================================================================

-- ---------------------------------------------------------------------------
-- sys_user 用户表
-- ---------------------------------------------------------------------------
CREATE TABLE `sys_user` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username`          VARCHAR(50)     NOT NULL                COMMENT '登录用户名，唯一',
    `password`          VARCHAR(100)    NOT NULL                COMMENT '密码（BCrypt 加密后的密文，禁止存明文）',
    `nickname`          VARCHAR(50)     NOT NULL                COMMENT '昵称，界面展示用',
    `phone`             VARCHAR(20)     NULL     DEFAULT NULL   COMMENT '手机号',
    `email`             VARCHAR(100)    NULL     DEFAULT NULL   COMMENT '邮箱',
    `avatar`            VARCHAR(255)    NULL     DEFAULT NULL   COMMENT '头像URL',
    `status`            TINYINT         NOT NULL DEFAULT 1      COMMENT '账号状态：0=禁用 1=启用',
    `last_login_time`   DATETIME        NULL     DEFAULT NULL   COMMENT '最后登录时间',
    `create_by`         BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '创建人ID',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '更新人ID',
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '用户表';

-- ---------------------------------------------------------------------------
-- sys_role 角色表
-- ---------------------------------------------------------------------------
CREATE TABLE `sys_role` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `role_code`     VARCHAR(50)     NOT NULL                COMMENT '角色编码，如 ROLE_AUTHOR / ROLE_EDITOR / ROLE_CHIEF / ROLE_ADMIN',
    `role_name`     VARCHAR(50)     NOT NULL                COMMENT '角色名称',
    `description`   VARCHAR(255)    NULL     DEFAULT NULL   COMMENT '角色描述',
    `sort`          INT             NOT NULL DEFAULT 0      COMMENT '排序号',
    `status`        TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：0=停用 1=启用',
    `create_by`     BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '创建人ID',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '更新人ID',
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '角色表';

-- ---------------------------------------------------------------------------
-- sys_permission 权限表（细粒度到接口级）
-- ---------------------------------------------------------------------------
CREATE TABLE `sys_permission` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `permission_code`   VARCHAR(100)    NOT NULL                COMMENT '权限编码，格式 资源:动作，如 article:audit',
    `permission_name`   VARCHAR(50)     NOT NULL                COMMENT '权限名称',
    `type`              TINYINT         NOT NULL DEFAULT 3      COMMENT '类型：1=菜单 2=按钮 3=接口',
    `parent_id`         BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '父级ID，0 表示顶级',
    `path`              VARCHAR(200)    NULL     DEFAULT NULL   COMMENT '前端路由或接口路径',
    `method`            VARCHAR(10)     NULL     DEFAULT NULL   COMMENT 'HTTP方法（type=3 时有值）',
    `sort`              INT             NOT NULL DEFAULT 0      COMMENT '排序号',
    `status`            TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：0=停用 1=启用',
    `create_by`         BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '创建人ID',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '更新人ID',
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_permission_code` (`permission_code`),
    KEY `idx_parent_id` (`parent_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '权限表';

-- ---------------------------------------------------------------------------
-- sys_user_role 用户角色关联表
-- ---------------------------------------------------------------------------
CREATE TABLE `sys_user_role` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`       BIGINT UNSIGNED NOT NULL                COMMENT '用户ID（逻辑外键 sys_user.id）',
    `role_id`       BIGINT UNSIGNED NOT NULL                COMMENT '角色ID（逻辑外键 sys_role.id）',
    `create_by`     BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '创建人ID',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '更新人ID',
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
    KEY `idx_role_id` (`role_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '用户角色关联表';

-- ---------------------------------------------------------------------------
-- sys_role_permission 角色权限关联表
-- ---------------------------------------------------------------------------
CREATE TABLE `sys_role_permission` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `role_id`           BIGINT UNSIGNED NOT NULL                COMMENT '角色ID（逻辑外键 sys_role.id）',
    `permission_id`     BIGINT UNSIGNED NOT NULL                COMMENT '权限ID（逻辑外键 sys_permission.id）',
    `create_by`         BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '创建人ID',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '更新人ID',
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_permission` (`role_id`, `permission_id`),
    KEY `idx_permission_id` (`permission_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '角色权限关联表';

-- ---------------------------------------------------------------------------
-- sys_apply_approval 授权申请审批表（角色申请 / 权限变更）
-- ---------------------------------------------------------------------------
CREATE TABLE `sys_apply_approval` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `apply_type`        TINYINT         NOT NULL                COMMENT '申请类型：1=角色申请 2=权限变更',
    `applicant_id`      BIGINT UNSIGNED NOT NULL                COMMENT '申请人ID（逻辑外键 sys_user.id）',
    `target_user_id`    BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '目标用户ID（角色申请时=申请人；权限变更时=被变更人）',
    `target_role_id`    BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '目标角色ID（逻辑外键 sys_role.id）',
    `apply_content`     JSON            NULL     DEFAULT NULL   COMMENT '申请内容快照（变更前后值）',
    `apply_reason`      VARCHAR(500)    NULL     DEFAULT NULL   COMMENT '申请理由',
    `status`            TINYINT         NOT NULL DEFAULT 0      COMMENT '审批状态：0=待审批 1=已通过 2=已驳回',
    `approver_id`       BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '审批人ID（逻辑外键 sys_user.id）',
    `approve_opinion`   VARCHAR(500)    NULL     DEFAULT NULL   COMMENT '审批意见',
    `approve_time`      DATETIME        NULL     DEFAULT NULL   COMMENT '审批时间',
    `create_by`         BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '创建人ID',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '更新人ID',
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_applicant` (`applicant_id`),
    KEY `idx_status_time` (`status`, `create_time`),
    KEY `idx_target_user` (`target_user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '授权申请审批表';

-- ============================================================================
--  三、内容域（4 张表）  主责：成员B
-- ============================================================================

-- ---------------------------------------------------------------------------
-- cms_category 分类表（两级树）
-- ---------------------------------------------------------------------------
CREATE TABLE `cms_category` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `parent_id`     BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '父分类ID，0=顶级分类',
    `category_name` VARCHAR(50)     NOT NULL                COMMENT '分类名称',
    `level`         TINYINT         NOT NULL DEFAULT 1      COMMENT '层级：1=一级 2=二级',
    `sort`          INT             NOT NULL DEFAULT 0      COMMENT '排序号',
    `description`   VARCHAR(255)    NULL     DEFAULT NULL   COMMENT '分类描述',
    `status`        TINYINT         NOT NULL DEFAULT 1      COMMENT '启用状态：0=停用 1=启用',
    `audit_status`  TINYINT         NOT NULL DEFAULT 1      COMMENT '审批状态：0=待审批 1=已生效 2=已驳回',
    `create_by`     BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '创建人ID',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '更新人ID',
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_parent_id` (`parent_id`),
    KEY `idx_status_sort` (`status`, `sort`),
    KEY `idx_category_name` (`category_name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '内容分类表';

-- ---------------------------------------------------------------------------
-- cms_category_audit_log 分类审批流水表
-- ---------------------------------------------------------------------------
CREATE TABLE `cms_category_audit_log` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `category_id`   BIGINT UNSIGNED NOT NULL                COMMENT '分类ID（逻辑外键 cms_category.id）',
    `audit_stage`   TINYINT         NOT NULL                COMMENT '审批环节：1=提交申请 2=管理员审批',
    `auditor_id`    BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '操作人ID（逻辑外键 sys_user.id）',
    `result`        TINYINT         NOT NULL                COMMENT '结论：1=通过 2=驳回',
    `opinion`       VARCHAR(500)    NULL     DEFAULT NULL   COMMENT '审批意见',
    `audit_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审批时间',
    `create_by`     BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '创建人ID',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '更新人ID',
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_category_stage` (`category_id`, `audit_stage`),
    KEY `idx_audit_time` (`audit_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '分类审批流水表';

-- ---------------------------------------------------------------------------
-- cms_tag 标签表
-- ---------------------------------------------------------------------------
CREATE TABLE `cms_tag` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tag_name`      VARCHAR(50)     NOT NULL                COMMENT '标签名称',
    `use_count`     INT             NOT NULL DEFAULT 0      COMMENT '被引用次数（冗余字段，用于热度排序）',
    `status`        TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：0=停用 1=启用',
    `create_by`     BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '创建人ID',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '更新人ID',
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tag_name` (`tag_name`),
    KEY `idx_use_count` (`use_count`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '标签表';

-- ---------------------------------------------------------------------------
-- cms_article_tag 文章标签关联表
-- ---------------------------------------------------------------------------
CREATE TABLE `cms_article_tag` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `article_id`    BIGINT UNSIGNED NOT NULL                COMMENT '文章ID（逻辑外键 cms_article.id）',
    `tag_id`        BIGINT UNSIGNED NOT NULL                COMMENT '标签ID（逻辑外键 cms_tag.id）',
    `create_by`     BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '创建人ID',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '更新人ID',
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_article_tag` (`article_id`, `tag_id`),
    KEY `idx_tag_id` (`tag_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '文章标签关联表';

-- ============================================================================
--  四、文章域（3 张表）  主责：成员A
-- ============================================================================

-- ---------------------------------------------------------------------------
-- cms_article 文章主表
-- status 取值见 docs/状态机图.md（10 态状态机）
-- ---------------------------------------------------------------------------
CREATE TABLE `cms_article` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `title`             VARCHAR(200)    NOT NULL                COMMENT '文章标题',
    `summary`           VARCHAR(500)    NULL     DEFAULT NULL   COMMENT '摘要',
    `cover_url`         VARCHAR(255)    NULL     DEFAULT NULL   COMMENT '封面图URL',
    `content`           LONGTEXT        NULL     DEFAULT NULL   COMMENT '正文内容',
    `category_id`       BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '分类ID（逻辑外键 cms_category.id）',
    `status`            TINYINT         NOT NULL DEFAULT 0      COMMENT '文章状态：0=草稿 1=待编辑初审 2=待主编终审 3=已通过待发布 4=已驳回 5=已发布 6=定时待发 7=已撤回 8=已下架 9=已归档',
    `author_id`         BIGINT UNSIGNED NOT NULL                COMMENT '作者ID（逻辑外键 sys_user.id）',
    `publisher_id`      BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '发布人ID（逻辑外键 sys_user.id）',
    `publish_time`      DATETIME        NULL     DEFAULT NULL   COMMENT '实际发布时间',
    `schedule_time`     DATETIME        NULL     DEFAULT NULL   COMMENT '排期发布时间',
    `is_top`            TINYINT         NOT NULL DEFAULT 0      COMMENT '是否置顶：0=否 1=是',
    `view_count`        INT             NOT NULL DEFAULT 0      COMMENT '浏览量',
    `version`           INT             NOT NULL DEFAULT 0      COMMENT '乐观锁版本号（MyBatis-Plus @Version）',
    `create_by`         BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '创建人ID',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '更新人ID',
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`),
    KEY `idx_category_id` (`category_id`),
    KEY `idx_author_id` (`author_id`),
    KEY `idx_status_create_time` (`status`, `create_time`),
    KEY `idx_status_schedule_time` (`status`, `schedule_time`),
    KEY `idx_title` (`title`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '文章主表';

-- ---------------------------------------------------------------------------
-- cms_article_audit_log 文章审核流水表
-- ---------------------------------------------------------------------------
CREATE TABLE `cms_article_audit_log` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `article_id`        BIGINT UNSIGNED NOT NULL                COMMENT '文章ID（逻辑外键 cms_article.id）',
    `audit_stage`       TINYINT         NOT NULL                COMMENT '审核环节：1=编辑初审 2=主编终审',
    `auditor_id`        BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '审核人ID（逻辑外键 sys_user.id）',
    `result`            TINYINT         NOT NULL                COMMENT '审核结论：1=通过 2=驳回',
    `opinion`           VARCHAR(500)    NULL     DEFAULT NULL   COMMENT '审核意见（驳回时必填）',
    `audit_time`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审核时间',
    `article_version`   INT             NULL     DEFAULT NULL   COMMENT '审核时的文章版本号（对应 cms_article_version.version_no）',
    `create_by`         BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '创建人ID',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '更新人ID',
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_article_stage` (`article_id`, `audit_stage`),
    KEY `idx_audit_time` (`audit_time`),
    KEY `idx_auditor_id` (`auditor_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '文章审核流水表';

-- ---------------------------------------------------------------------------
-- cms_article_version 文章版本快照表
-- 注意：本表为只增不改的快照表，只有 create_time，无 update_by/update_time
--       因此实体类不继承 BaseEntity
-- ---------------------------------------------------------------------------
CREATE TABLE `cms_article_version` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `article_id`        BIGINT UNSIGNED NOT NULL                COMMENT '文章ID（逻辑外键 cms_article.id）',
    `version_no`        INT             NOT NULL                COMMENT '版本号，从 1 递增',
    `title_snapshot`    VARCHAR(200)    NOT NULL                COMMENT '标题快照',
    `content_snapshot`  LONGTEXT        NULL     DEFAULT NULL   COMMENT '正文快照',
    `modifier_id`       BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '修改人ID（逻辑外键 sys_user.id）',
    `modify_remark`     VARCHAR(255)    NULL     DEFAULT NULL   COMMENT '修改说明',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`           TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_article_version` (`article_id`, `version_no`),
    KEY `idx_modifier_id` (`modifier_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '文章版本快照表';

-- ============================================================================
--  五、日志域（4 张表）  主责：成员D
-- ============================================================================

-- ---------------------------------------------------------------------------
-- sys_oper_log 操作日志表（由 AOP 切面自动写入）
-- ---------------------------------------------------------------------------
CREATE TABLE `sys_oper_log` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `module`            VARCHAR(50)     NULL     DEFAULT NULL   COMMENT '操作模块，如 文章 / 分类 / 权限',
    `oper_type`         VARCHAR(20)     NULL     DEFAULT NULL   COMMENT '操作类型：INSERT/UPDATE/DELETE/AUDIT/EXPORT/OTHER',
    `oper_desc`         VARCHAR(200)    NULL     DEFAULT NULL   COMMENT '操作描述',
    `request_method`    VARCHAR(10)     NULL     DEFAULT NULL   COMMENT 'HTTP方法',
    `request_url`       VARCHAR(255)    NULL     DEFAULT NULL   COMMENT '请求URL',
    `request_param`     TEXT            NULL     DEFAULT NULL   COMMENT '请求参数（JSON，已脱敏）',
    `response_result`   TEXT            NULL     DEFAULT NULL   COMMENT '响应结果（截断存储）',
    `oper_id`           BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '操作人ID（逻辑外键 sys_user.id）',
    `oper_name`         VARCHAR(50)     NULL     DEFAULT NULL   COMMENT '操作人昵称（冗余，避免关联查询）',
    `oper_ip`           VARCHAR(50)     NULL     DEFAULT NULL   COMMENT '操作人IP',
    `cost_time`         BIGINT          NULL     DEFAULT NULL   COMMENT '耗时（毫秒）',
    `status`            TINYINT         NOT NULL DEFAULT 1      COMMENT '操作状态：1=成功 2=失败',
    `error_msg`         VARCHAR(1000)   NULL     DEFAULT NULL   COMMENT '错误信息（失败时记录）',
    `oper_time`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    `create_by`         BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '创建人ID',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '更新人ID',
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_oper_time` (`oper_time`),
    KEY `idx_oper_id_time` (`oper_id`, `oper_time`),
    KEY `idx_module_type` (`module`, `oper_type`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '操作日志表';

-- ---------------------------------------------------------------------------
-- sys_login_log 登录日志表
-- ---------------------------------------------------------------------------
CREATE TABLE `sys_login_log` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username`      VARCHAR(50)     NULL     DEFAULT NULL   COMMENT '登录用户名（失败时也可能不存在）',
    `user_id`       BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '用户ID（逻辑外键 sys_user.id，登录成功时有值）',
    `ip`            VARCHAR(50)     NULL     DEFAULT NULL   COMMENT '登录IP',
    `user_agent`    VARCHAR(500)    NULL     DEFAULT NULL   COMMENT '浏览器UA',
    `login_status`  TINYINT         NOT NULL                COMMENT '登录结果：1=成功 2=失败',
    `message`       VARCHAR(255)    NULL     DEFAULT NULL   COMMENT '提示信息（失败原因）',
    `login_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
    `create_by`     BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '创建人ID',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '更新人ID',
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_username` (`username`),
    KEY `idx_login_time` (`login_time`),
    KEY `idx_status_time` (`login_status`, `login_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '登录日志表';

-- ---------------------------------------------------------------------------
-- cms_article_index 文章检索索引表（检索域专用，发布/下线时同步维护）
-- ---------------------------------------------------------------------------
CREATE TABLE `cms_article_index` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `article_id`    BIGINT UNSIGNED NOT NULL                COMMENT '文章ID（逻辑外键 cms_article.id）',
    `title`         VARCHAR(200)    NOT NULL                COMMENT '标题（冗余，避免回表）',
    `keywords`      VARCHAR(500)    NULL     DEFAULT NULL   COMMENT '关键词（空格分隔，用于检索匹配）',
    `category_id`   BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '分类ID（冗余）',
    `hot_score`     INT             NOT NULL DEFAULT 0      COMMENT '热度分（浏览量+发布时间加权）',
    `index_status`  TINYINT         NOT NULL DEFAULT 1      COMMENT '索引状态：0=已失效 1=有效',
    `update_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '索引更新时间',
    `create_by`     BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '创建人ID',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '更新人ID',
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_article_id` (`article_id`),
    KEY `idx_title` (`title`),
    KEY `idx_category` (`category_id`),
    KEY `idx_hot_score` (`hot_score`),
    KEY `idx_index_status` (`index_status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '文章检索索引表';

-- ---------------------------------------------------------------------------
-- sys_report 统计报表表
-- ---------------------------------------------------------------------------
CREATE TABLE `sys_report` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `report_name`       VARCHAR(100)    NOT NULL                COMMENT '报表名称',
    `report_type`       TINYINT         NOT NULL                COMMENT '报表类型：1=文章状态分布 2=分类分布 3=审核效率 4=用户活跃 9=自定义',
    `stat_period`       VARCHAR(50)     NULL     DEFAULT NULL   COMMENT '统计周期，如 2026-10 或 2026-10-05~2026-10-11',
    `result_json`       JSON            NULL     DEFAULT NULL   COMMENT '统计结果JSON快照',
    `generator_id`      BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '生成人ID（逻辑外键 sys_user.id）',
    `generate_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    `create_by`         BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '创建人ID',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         BIGINT UNSIGNED NULL     DEFAULT NULL   COMMENT '更新人ID',
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_report_type` (`report_type`),
    KEY `idx_generate_time` (`generate_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '统计报表表';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================================
--  六、字典与演示数据
--
--  ⚠️⚠️ 关于密码哈希（实测踩坑，务必阅读）
--
--  下面 5 个账号的密码明文**均为 123456**，密文是「由 BCryptPasswordEncoder
--  真实生成后粘贴」的，可直接登录成功。
--
--  为什么必须强调这一点：本脚本早期版本里的哈希是**手写编造的字符串** ——
--  它的格式完全合法（60 位、$2a$10$ 开头），但**不是任何密码的哈希**，
--  因此登录永远返回 "用户名或密码错误"，而排查方向会被引向"代码问题"。
--  实测验证过：用 BCryptPasswordEncoder.matches("123456", 假哈希) → false。
--
--  【教训】演示数据中的密码哈希绝不能手写。必须用目标框架的编码器生成，
--  并实测 matches() 通过后再写入脚本。生成方式：
--      String hash = new BCryptPasswordEncoder().encode("123456");
--
--  ⚠️ BCrypt 每次加密使用随机盐，因此同一明文每次生成的密文都不同 ——
--  下面 5 个账号的密文**互不相同是正确的**，不要"为了方便"改回同一串。
-- ============================================================================

-- ---------- 角色 ----------
INSERT INTO `sys_role` (`id`, `role_code`, `role_name`, `description`, `sort`, `status`) VALUES
(1, 'ROLE_AUTHOR', '投稿人', '撰写与维护自己的稿件，可提交审核、撤回自己的文章', 10, 1),
(2, 'ROLE_EDITOR', '编辑',     '负责内容初审，对待编辑初审的文章进行通过或驳回', 20, 1),
(3, 'ROLE_CHIEF',  '主编',     '负责终审与发布决策，可发布、排期、下架、归档文章', 30, 1),
(4, 'ROLE_ADMIN',  '管理员',   '系统与权限治理，审批授权申请，管理用户与权限', 40, 1);

-- ---------- 权限（细粒度到接口级） ----------
INSERT INTO `sys_permission` (`id`, `permission_code`, `permission_name`, `type`, `parent_id`, `path`, `method`, `sort`, `status`) VALUES
(1,  'article:view',    '查看文章',  3, 0, '/api/v1/articles',        'GET',    10, 1),
(2,  'article:create',  '创建文章',  3, 0, '/api/v1/articles/draft',  'POST',   20, 1),
(3,  'article:update',  '编辑文章',  3, 0, '/api/v1/articles/{id}',   'PUT',    30, 1),
(4,  'article:submit',  '提交审核',  3, 0, '/api/v1/articles/{id}/submit', 'POST', 40, 1),
(5,  'article:audit',   '文章审核',  3, 0, '/api/v1/articles/{id}/audit',  'POST', 50, 1),
(6,  'article:publish', '发布文章',  3, 0, '/api/v1/articles/{id}/publish','POST', 60, 1),
(7,  'article:withdraw','撤回文章',  3, 0, '/api/v1/articles/{id}/withdraw','POST',70, 1),
(8,  'article:manage',  '下架归档',  3, 0, '/api/v1/articles/{id}/offline','POST', 80, 1),
(9,  'article:delete',  '删除文章',  3, 0, '/api/v1/articles',        'DELETE', 90, 1),
(10, 'category:view',   '查看分类',  3, 0, '/api/v1/categories',      'GET',   100, 1),
(11, 'category:manage', '分类管理',  3, 0, '/api/v1/categories',      'POST',  110, 1),
(12, 'category:audit',  '分类审批',  3, 0, '/api/v1/categories/{id}/audit','POST',120, 1),
(13, 'user:view',       '查看用户',  3, 0, '/api/v1/users',           'GET',   130, 1),
(14, 'user:manage',     '用户管理',  3, 0, '/api/v1/users',           'POST',  140, 1),
(15, 'role:manage',     '角色管理',  3, 0, '/api/v1/roles',           'POST',  150, 1),
(16, 'permission:manage','权限管理', 3, 0, '/api/v1/permissions',     'POST',  160, 1),
(17, 'approval:audit',  '授权审批',  3, 0, '/api/v1/approvals/{id}/audit','POST',170, 1),
(18, 'log:view',        '查看日志',  3, 0, '/api/v1/logs',            'GET',   180, 1),
(19, 'report:view',     '查看报表',  3, 0, '/api/v1/reports',         'GET',   190, 1),
(20, 'report:export',   '导出报表',  3, 0, '/api/v1/reports/export',  'GET',   200, 1);

-- ---------- 用户 ----------
-- 密码明文均为 123456；下列密文由 BCryptPasswordEncoder.encode("123456") 真实生成
-- （5 个密文互不相同是正常的 —— BCrypt 每次随机加盐；原因与坑见上方段落）
INSERT INTO `sys_user` (`id`, `username`, `password`, `nickname`, `phone`, `email`, `status`) VALUES
(1, 'author',   '$2a$10$3hSktzLWiaZzqh8Z..zUhOMYXMgWQaC76yUzhuUZ7l9SlQ/sPWr5G', '张晓投稿', '13800000001', 'author@cms.local', 1),
(2, 'editor',   '$2a$10$SsaNtVIaNF6glrP8clOwO.nWS9NhD7sEPX.C4nJXJTU4kdArPWCiu', '李编辑',   '13800000002', 'editor@cms.local', 1),
(3, 'chief',    '$2a$10$CjWfI1a68tejTmxtvXwOIerq4abAkei3f1pdw1NeO4F1nHY1zXKI6', '王主编',   '13800000003', 'chief@cms.local',  1),
(4, 'admin',    '$2a$10$LiulYA8oOJl5li1xTRJfauzp5uaE79d8FtPVCruuqhBQgDwnt51KW', '系统管理员','13800000004', 'admin@cms.local',  1),
(5, 'author2',  '$2a$10$4wEYDW30CTDAnv4ocgcbv.mIC1/c8mVr9sHD3VK5wA1AI.3M1KD3O', '赵投稿',   '13800000005', 'author2@cms.local',1);

-- ---------- 用户角色分配 ----------
INSERT INTO `sys_user_role` (`user_id`, `role_id`) VALUES
(1, 1),   -- author  → 投稿人
(2, 2),   -- editor  → 编辑
(3, 3),   -- chief   → 主编
(4, 4),   -- admin   → 管理员
(5, 1);   -- author2 → 投稿人（用于演示水平越权拦截）

-- ---------- 角色权限分配 ----------
-- 投稿人：查看 + 创建 + 编辑 + 提交 + 撤回自己的文章
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`) VALUES
(1, 1), (1, 2), (1, 3), (1, 4), (1, 7), (1, 9), (1, 10);
-- 编辑：投稿人全部 + 审核 + 分类查看
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`) VALUES
(2, 1), (2, 2), (2, 3), (2, 4), (2, 7), (2, 9), (2, 10), (2, 5), (2, 11);
-- 主编：全部文章权限 + 分类管理 + 报表
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`) VALUES
(3, 1), (3, 2), (3, 3), (3, 4), (3, 5), (3, 6), (3, 7), (3, 8), (3, 9), (3, 10), (3, 11), (3, 19);
-- 管理员：全部
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`) VALUES
(4, 1),(4, 2),(4, 3),(4, 4),(4, 5),(4, 6),(4, 7),(4, 8),(4, 9),(4, 10),
(4, 11),(4, 12),(4, 13),(4, 14),(4, 15),(4, 16),(4, 17),(4, 18),(4, 19),(4, 20);

-- ---------- 分类（两级） ----------
INSERT INTO `cms_category` (`id`, `parent_id`, `category_name`, `level`, `sort`, `description`, `status`, `audit_status`) VALUES
(1, 0, '技术资讯',   1, 10, '技术类内容', 1, 1),
(2, 0, '产品动态',   1, 20, '产品类内容', 1, 1),
(3, 0, '公司新闻',   1, 30, '公司内部新闻', 1, 1),
(11, 1, '前端开发',  2, 11, 'Vue / React / 工程化', 1, 1),
(12, 1, '后端开发',  2, 12, 'Java / Spring / 数据库', 1, 1),
(13, 1, '人工智能',  2, 13, 'AI / 大模型 / 算法', 1, 1),
(21, 2, '版本发布',  2, 21, '产品版本更新说明', 1, 1),
(22, 2, '功能预告',  2, 22, '即将上线的功能', 1, 1),
(31, 3, '企业公告',  2, 31, '公司级公告', 1, 1);

-- ---------- 标签 ----------
INSERT INTO `cms_tag` (`id`, `tag_name`, `use_count`, `status`) VALUES
(1, 'Spring', 3, 1), (2, 'MyBatis-Plus', 2, 1), (3, 'Vue3', 2, 1),
(4, '性能优化', 2, 1), (5, '最佳实践', 3, 1), (6, '踩坑记录', 2, 1),
(7, '架构设计', 2, 1), (8, 'SQL优化', 1, 1), (9, '权限模型', 1, 1),
(10, '状态机', 2, 1);

-- ---------- 文章（覆盖 10 种状态） ----------
INSERT INTO `cms_article`
(`id`, `title`, `summary`, `content`, `category_id`, `status`, `author_id`, `publisher_id`, `publish_time`, `schedule_time`, `is_top`, `view_count`, `create_time`, `update_time`) VALUES
-- 草稿
(1, 'Spring 事务失效的六种场景与排查方法', '整理了事务不生效的典型场景与定位思路。',
 '在实际开发中，事务不生效往往不是配置问题，而是使用方式问题。最常见的六种场景是：异常被 try-catch 吞掉、方法内部自调用绕过代理、注解加在 private 方法上、rollbackFor 未指定、异常类型不匹配、以及多数据源未指定事务管理器。\n\n排查思路是从 AOP 代理是否生效入手，先确认调用是否走了代理对象，再确认异常是否被吞掉。',
 12, 0, 1, NULL, NULL, NULL, 0, 12, '2026-10-01 09:12:00', '2026-10-02 14:20:00'),
(2, 'Vue3 组合式 API 的十条实践建议', '关于 script setup 与响应式的一些经验总结。',
 '组合式 API 带来的最大变化是把逻辑按功能聚合，而不是按选项分散。这十条建议围绕可读性展开。\n\n第一条，优先用 ref 而不是 reactive，因为 ref 的替换语义更清晰。第二条，把可复用的逻辑抽成 composable。',
 11, 0, 5, NULL, NULL, NULL, 0, 3, '2026-10-02 10:30:00', '2026-10-02 10:30:00'),
-- 待编辑初审
(3, 'MyBatis-Plus 分页插件的三个隐藏坑', '分页插件在自定义 SQL 上的行为与预期不同。',
 '分页插件对自定义 JOIN 查询的 count 语句处理需要特别注意。当 SQL 里包含 group by 时，自动生成的 count 语句可能不准确。\n\n另外，maxLimit 不设置的话，前端传一个很大的 size 会直接导致慢查询。',
 12, 1, 1, NULL, NULL, NULL, 0, 45, '2026-10-03 11:00:00', '2026-10-03 15:30:00'),
(4, '从零搭建前端工程化的最小可行方案', 'Vite + Vue3 + Element Plus 的工程化起步。',
 '工程化的第一步不是上工具链，而是统一目录约定。目录不能统一，后面所有规范都是空的。\n\n第二步是收敛请求层，把统一返回体的解包、401 跳登录、错误提示都收在一个 axios 实例里。',
 11, 1, 5, NULL, NULL, NULL, 0, 21, '2026-10-03 14:20:00', '2026-10-04 09:10:00'),
-- 待主编终审
(5, 'RBAC 权限模型在中小型系统中的落地', '角色权限模型的设计取舍与简化方案。',
 '标准 RBAC 有用户、角色、权限、用户角色、角色权限五张表，对于中小型系统来说这个模型足够用。\n\n关键设计点在于权限的粒度：如果权限只到菜单级，无法实现按钮级控制；如果细化到接口级，权限表会迅速膨胀。本项目的做法是权限编码采用 资源:动作 的形式，既表达清晰又便于扩展。',
 12, 2, 1, NULL, NULL, NULL, 0, 88, '2026-09-30 16:40:00', '2026-10-04 11:00:00'),
(6, '大模型辅助编码的边界在哪里', '关于 AI 生成代码的可信度与人工校验成本。',
 'AI 生成代码的效率提升是真实的，但它对边界条件的处理明显弱于对主干逻辑的处理。\n\n实践下来，可用的部分是骨架与样板代码，必须人工处理的是并发、事务、异常与安全边界。',
 13, 2, 5, NULL, NULL, NULL, 0, 132, '2026-09-29 09:00:00', '2026-10-04 15:20:00'),
-- 已通过待发布
(7, '状态机集中管理的工程价值', '为什么状态流转规则不该散落在 if-else 里。',
 '当业务状态超过五个时，散落的 if-else 判断会迅速失控。识别规则是：同一个状态判断在多处出现，且每处写法略有差异。\n\n集中管理的收益在于可穷举验证。把全部合法流转登记在一张静态映射表后，测试可以按组合覆盖，评审时也能一眼看出全部规则。',
 12, 3, 1, NULL, NULL, NULL, 1, 67, '2026-09-28 10:20:00', '2026-10-05 09:30:00'),
(8, '产品 V2.4 版本发布说明', '本次发布的八项功能更新。',
 '本次发布包含八项功能更新与十二项缺陷修复，重点优化了内容审核路径与权限配置体验。\n\n内容审核路径方面，新增批量提交能力，并支持在审核列表直接查看历史审核意见。',
 21, 3, 5, NULL, NULL, NULL, 0, 205, '2026-09-27 14:00:00', '2026-10-05 10:10:00'),
-- 已驳回
(9, '关于数据库索引的一些误解', '索引不是越多越好，这里说清原因。',
 '一个常见误解是"给经常查询的字段都加上索引就能变快"。实际上索引会拖慢写入，并且占用额外存储。\n\n联合索引要遵循最左前缀原则，这一点在写查询条件时最容易被忽略。',
 12, 4, 1, NULL, NULL, NULL, 0, 9, '2026-10-04 08:40:00', '2026-10-05 16:00:00'),
(10, '前端组件封装的颗粒度问题', '过度封装与封装不足之间的平衡。',
 '组件封装的颗粒度没有统一答案，但有一条判断标准：如果同一个组件在不同页面上需要传三组以上不同的 props 才能满足需求，说明封装边界错了。',
 11, 4, 5, NULL, NULL, NULL, 0, 6, '2026-10-04 13:10:00', '2026-10-05 16:30:00'),
-- 已发布
(11, 'Spring 5.3 全注解整合的完整流程', '从零到一的 SSM 整合步骤与踩坑记录。',
 '全注解整合的关键是理解父子容器的职责划分。根容器负责 Service、Mapper 与事务，子容器负责 Controller，两者扫描范围不能重叠，否则会出现 Bean 被重复创建的问题。\n\n另一个常见问题是 Mapper XML 的扫描路径，如果放错目录，启动时报 Mapper 注入失败。',
 12, 5, 1, 3, '2026-09-25 10:00:00', NULL, 1, 412, '2026-09-24 09:00:00', '2026-09-25 10:00:00'),
(12, '内容运营后台的信息架构设计', '后台系统的导航与信息层级组织方法。',
 '信息架构决定了后台好不好用。核心原则是按用户的作业流程组织导航，而不是按数据库表组织。\n\n以内容审核为例，编辑关心的是"待我审核"，而不是"文章表"，因此入口应该按待办组织。',
 22, 5, 5, 3, '2026-09-26 15:30:00', NULL, 0, 187, '2026-09-26 09:20:00', '2026-09-26 15:30:00'),
(13, 'AOP 切面的执行顺序陷阱', '多个切面同时作用于一个方法时的顺序问题。',
 '当多个切面作用于同一方法时，执行顺序由 @Order 或 Ordered 接口决定，未指定时顺序不确定。\n\n实践中看到的问题多是日志切面与事务切面的顺序错乱，导致日志记录的耗时不含事务提交时间。',
 12, 5, 1, 3, '2026-09-27 11:00:00', NULL, 0, 96, '2026-09-27 09:40:00', '2026-09-27 11:00:00'),
-- 定时待发
-- ⚠️ 排期时间必须是"固定"的将来时间，绝不能用 DATE_ADD(NOW(), INTERVAL n MINUTE)。
--    S14 修正：原先写 DATE_ADD(NOW(), INTERVAL 5 MINUTE)，本意是顺带演示定时发布，
--    实际后果是：脚本一执行，5 分钟后 @Scheduled（cron 0 * * * * ?，每分钟扫一次）
--    就真的把这篇发出去了 —— 于是"定时待发(6)"这一态在演示数据里永久归零，
--    20 篇种子从"覆盖 10 态"退化成"覆盖 9 态"。
--    ⚠️ 而且它不会报错、不会留痕（publishScheduled 是正常业务成功日志），
--    只有把 id=14 的 status 读出来才会发现，属于典型的静默失效。
--    要做定时发布演示时，临时把这列 UPDATE 成 NOW() + 1 分钟即可，
--    走接口的话用 POST /articles/{id}/schedule（S13 实测记录里有现成命令）。
(14, '企业公告：国庆假期值班安排', '假期期间的值班与应急联系安排。',
 '假期期间各部门安排好值班人员，确保系统稳定运行。如遇线上问题，按应急预案分级响应。\n\n值班表已同步至内部系统，请各位同事查阅确认。',
 31, 6, 5, NULL, NULL, '2026-12-31 23:59:59', 0, 0, '2026-10-05 10:00:00', '2026-10-05 10:00:00'),
-- 已撤回
(15, '旧版接口迁移指南', '接口从 v0 升级到 v1 的迁移说明。',
 '本次接口升级统一了返回结构与状态码，旧接口保留一个版本周期后下线。\n\n迁移要点：URL 前缀统一加 /api/v1，返回结构统一为 code/message/data 三段式。',
 21, 7, 1, 3, '2026-09-20 09:00:00', NULL, 0, 78, '2026-09-19 14:00:00', '2026-09-22 10:00:00'),
(16, '测试环境使用说明', '测试环境的访问方式与注意事项。',
 '测试环境数据每日重置，请勿在测试环境录入重要数据。\n\n访问地址与账号请查阅内部文档，测试环境的问题请在群里反馈。',
 31, 7, 5, 3, '2026-09-21 10:30:00', NULL, 0, 52, '2026-09-21 09:00:00', '2026-09-22 11:00:00'),
-- 已下架
(17, '已过期的活动预告', '活动已结束，内容保留备查。',
 '该活动已于上月底结束，本内容仅作留档，不再对外展示。',
 22, 8, 1, 3, '2026-09-10 09:00:00', NULL, 0, 143, '2026-09-09 10:00:00', '2026-09-30 16:00:00'),
(18, '临时维护通知（已作废）', '维护已完成，通知作废。',
 '系统维护已于当日凌晨完成，本通知作废。',
 31, 8, 5, 3, '2026-09-12 09:00:00', NULL, 0, 61, '2026-09-11 18:00:00', '2026-09-30 16:10:00'),
-- 已归档
(19, '2025 年度内容运营总结', '上一年度内容运营数据与结论。',
 '上一年度累计发布内容 1200 余篇，审核平均耗时 6 小时。\n\n本内容已归档，仅作历史记录保留。',
 31, 9, 1, 3, '2025-12-31 10:00:00', NULL, 0, 302, '2025-12-30 09:00:00', '2026-01-15 10:00:00'),
(20, '旧版登录页设计稿说明', '已废弃的设计方案留档。',
 '该设计稿已被新方案替代，归档备查。',
 31, 9, 5, 3, '2025-11-20 10:00:00', NULL, 0, 88, '2025-11-19 09:00:00', '2026-01-15 10:20:00');

-- ---------- 文章标签关联 ----------
INSERT INTO `cms_article_tag` (`article_id`, `tag_id`) VALUES
(1,1),(1,5),(1,6),(3,2),(3,4),(3,6),(5,7),(5,9),(7,10),(7,5),(8,5),(11,1),(11,5),(13,1),(13,4),(19,5);

-- ---------- 文章版本快照（每篇文章至少 1 条初始版本） ----------
INSERT INTO `cms_article_version` (`article_id`, `version_no`, `title_snapshot`, `content_snapshot`, `modifier_id`, `modify_remark`, `create_time`) VALUES
(1, 1, 'Spring 事务失效的六种场景与排查方法', '初稿内容。', 1, '创建草稿', '2026-10-01 09:12:00'),
(2, 1, 'Vue3 组合式 API 的十条实践建议', '初稿内容。', 5, '创建草稿', '2026-10-02 10:30:00'),
(3, 1, 'MyBatis-Plus 分页插件的三个隐藏坑', '初稿内容。', 1, '创建草稿', '2026-10-03 11:00:00'),
(5, 1, 'RBAC 权限模型在中小型系统中的落地', '初稿内容。', 1, '创建草稿', '2026-09-30 16:40:00'),
(7, 1, '状态机集中管理的工程价值', '初稿内容。', 1, '创建草稿', '2026-09-28 10:20:00'),
(7, 2, '状态机集中管理的工程价值（修订）', '补充了可穷举验证的部分。', 1, '更新草稿', '2026-09-29 10:20:00'),
(9, 1, '关于数据库索引的一些误解', '初稿内容。', 1, '创建草稿', '2026-10-04 08:40:00'),
(11, 1, 'Spring 5.3 全注解整合的完整流程', '初稿内容。', 1, '创建草稿', '2026-09-24 09:00:00'),
(15, 1, '旧版接口迁移指南', '初稿内容。', 1, '创建草稿', '2026-09-19 14:00:00'),
(19, 1, '2025 年度内容运营总结', '初稿内容。', 1, '创建草稿', '2025-12-30 09:00:00');

-- ---------- 文章审核流水（为已通过/已发布的文章补真实流水） ----------
INSERT INTO `cms_article_audit_log` (`article_id`, `audit_stage`, `auditor_id`, `result`, `opinion`, `audit_time`, `article_version`) VALUES
-- 文章 7：初审通过 → 终审通过 → 待发布（流水 2 条）
(7, 1, 2, 1, '内容结构清晰，可通过初审。', '2026-10-01 09:30:00', 2),
(7, 2, 3, 1, '同意发布，建议补充一段小结。', '2026-10-02 10:00:00', 2),
-- 文章 11：初审通过 → 终审通过 → 已发布（流水 2 条）
(11, 1, 2, 1, '技术细节准确，通过。', '2026-09-24 15:00:00', 1),
(11, 2, 3, 1, '同意发布。', '2026-09-25 09:00:00', 1),
-- 文章 12
(12, 1, 2, 1, '通过。', '2026-09-26 11:00:00', 1),
(12, 2, 3, 1, '同意发布。', '2026-09-26 15:00:00', 1),
-- 文章 13
(13, 1, 2, 1, '通过。', '2026-09-27 10:00:00', 1),
(13, 2, 3, 1, '同意发布。', '2026-09-27 11:00:00', 1),
-- 文章 9：被驳回（流水 1 条，演示驳回与意见）
(9, 1, 2, 2, '缺少实测数据支撑，请补充执行计划对比截图后重新提交。', '2026-10-05 15:30:00', 1),
-- 文章 10：被驳回
(10, 1, 2, 2, '案例描述过于笼统，建议补充具体的封装前后代码对比。', '2026-10-05 16:10:00', 1),
-- 文章 5：初审通过，等待终审（流水 1 条）
(5, 1, 2, 1, '权限粒度设计合理，通过初审。', '2026-10-04 10:30:00', 1),
-- 文章 6：初审通过，等待终审
(6, 1, 2, 1, '观点有一定深度，通过。', '2026-10-04 14:50:00', 1);

-- ---------- 文章检索索引（为已发布文章建立索引） ----------
INSERT INTO `cms_article_index` (`article_id`, `title`, `keywords`, `category_id`, `hot_score`, `index_status`) VALUES
(11, 'Spring 5.3 全注解整合的完整流程', 'Spring 注解 整合 SSM 父子容器', 12, 412, 1),
(12, '内容运营后台的信息架构设计', '信息架构 后台 导航 内容运营', 22, 187, 1),
(13, 'AOP 切面的执行顺序陷阱', 'AOP 切面 顺序 事务', 12, 96, 1);

-- ---------- 授权申请（一条待审批，用于演示审批流） ----------
INSERT INTO `sys_apply_approval` (`apply_type`, `applicant_id`, `target_user_id`, `target_role_id`, `apply_content`, `apply_reason`, `status`) VALUES
(1, 5, 5, 2, JSON_OBJECT('before', 'ROLE_AUTHOR', 'after', 'ROLE_EDITOR'),
 '因部门调整需要参与内容初审工作，申请编辑角色。', 0);

-- ---------- 操作日志与登录日志（少量演示数据） ----------
INSERT INTO `sys_oper_log` (`module`, `oper_type`, `oper_desc`, `request_method`, `request_url`, `oper_id`, `oper_name`, `oper_ip`, `cost_time`, `status`, `oper_time`) VALUES
('文章', 'UPDATE', '提交初审', 'POST', '/api/v1/articles/3/submit', 1, '张晓投稿', '127.0.0.1', 42, 1, '2026-10-03 15:30:00'),
('文章', 'AUDIT',  '文章审核', 'POST', '/api/v1/articles/9/audit',  2, '李编辑',   '127.0.0.1', 58, 1, '2026-10-05 15:30:00'),
('文章', 'UPDATE', '撤回文章', 'POST', '/api/v1/articles/15/withdraw', 1, '张晓投稿', '127.0.0.1', 36, 1, '2026-09-22 10:00:00'),
('文章', 'UPDATE', '下架文章', 'POST', '/api/v1/articles/17/offline', 3, '王主编',  '127.0.0.1', 47, 1, '2026-09-30 16:00:00');

INSERT INTO `sys_login_log` (`username`, `user_id`, `ip`, `user_agent`, `login_status`, `message`, `login_time`) VALUES
('author', 1, '127.0.0.1', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)', 1, '登录成功', '2026-10-05 09:00:00'),
('editor', 2, '127.0.0.1', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)', 1, '登录成功', '2026-10-05 09:05:00'),
('chief',  3, '127.0.0.1', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)', 1, '登录成功', '2026-10-05 09:10:00'),
('admin',  NULL, '127.0.0.1', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)', 2, '密码错误', '2026-10-05 09:12:00');

-- ---------- 统计报表示例 ----------
INSERT INTO `sys_report` (`report_name`, `report_type`, `stat_period`, `result_json`, `generator_id`, `generate_time`) VALUES
('文章状态分布统计', 1, '2026-10',
 JSON_OBJECT('草稿', 2, '待编辑初审', 2, '待主编终审', 2, '已通过待发布', 2, '已驳回', 2, '已发布', 3, '定时待发', 1, '已撤回', 2, '已下架', 2, '已归档', 2),
 4, '2026-10-05 17:00:00');

-- ============================================================================
--  七、执行完成校验
-- ============================================================================
SELECT '=== 建表结果 ===' AS `校验项`;
SELECT TABLE_NAME AS `表名`, TABLE_COMMENT AS `说明`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'cms_db'
ORDER BY TABLE_NAME;

SELECT COUNT(*) AS `表数量（应为 17）` FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'cms_db';
SELECT COUNT(*) AS `用户数（应为 5）` FROM `sys_user`;
SELECT COUNT(*) AS `角色数（应为 4）` FROM `sys_role`;
SELECT COUNT(*) AS `权限数（应为 20）` FROM `sys_permission`;
SELECT COUNT(*) AS `分类数（应为 9）` FROM `cms_category`;
SELECT COUNT(*) AS `标签数（应为 10）` FROM `cms_tag`;
SELECT COUNT(*) AS `文章数（应为 20）` FROM `cms_article`;
SELECT status AS `状态`, COUNT(*) AS `篇数` FROM `cms_article` GROUP BY status ORDER BY status;
SELECT COUNT(*) AS `审核流水数（应为 12）` FROM `cms_article_audit_log`;
SELECT COUNT(*) AS `版本快照数（应为 10）` FROM `cms_article_version`;
