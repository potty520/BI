-- ============================================================
-- 自研报表与BI平台 元数据库chema (H2 / MySQL 兼容)
-- 前缀约定：rbi_ 业务元数据；demo_ 演示业务数据
-- ============================================================

-- M09 用户 / 角色 / 菜单
CREATE TABLE IF NOT EXISTS rbi_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  password VARCHAR(128) NOT NULL,
  real_name VARCHAR(64),
  dept_id BIGINT,
  status INT DEFAULT 1,
  must_change_pwd INT DEFAULT 0,       -- 1 = 首次登录/重置后必须改密
  email VARCHAR(128),                  -- 找回密码用
  token_version INT DEFAULT 0,         -- 令牌版本：改密/踢下线时 +1，旧令牌立即失效
  mfa_secret VARCHAR(64),              -- TOTP 密钥（Base32）
  mfa_enabled INT DEFAULT 0,           -- 1 = 已启用二次验证
  pwd_updated_at TIMESTAMP,            -- 最近一次改密时间（密码过期判定）
  pwd_expire_days INT,                 -- 该用户的密码有效期（天），空则用全局配置
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 登录会话（支持单设备踢出）
CREATE TABLE IF NOT EXISTS rbi_user_session (
  jti VARCHAR(64) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  username VARCHAR(64),
  ip VARCHAR(64),
  user_agent VARCHAR(256),
  created_at TIMESTAMP,
  last_seen TIMESTAMP,
  expire_at TIMESTAMP,
  revoked INT DEFAULT 0
);
CREATE TABLE IF NOT EXISTS rbi_role (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_code VARCHAR(64) NOT NULL UNIQUE,
  role_name VARCHAR(64) NOT NULL,
  remark VARCHAR(256)
);
CREATE TABLE IF NOT EXISTS rbi_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id)
);
CREATE TABLE IF NOT EXISTS rbi_dept (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  parent_id BIGINT DEFAULT 0,
  sort_no INT DEFAULT 0
);

-- M09 目录 / 权限 / 调度 / 日志
CREATE TABLE IF NOT EXISTS rbi_directory (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  parent_id BIGINT DEFAULT 0,
  name VARCHAR(128) NOT NULL,
  type VARCHAR(32) DEFAULT 'folder',   -- folder / report / dashboard
  ref_id BIGINT,
  sort_no INT DEFAULT 0
);
CREATE TABLE IF NOT EXISTS rbi_permission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_id BIGINT NOT NULL,
  resource_type VARCHAR(32) NOT NULL,  -- directory / report
  resource_id BIGINT NOT NULL,
  perm VARCHAR(32) NOT NULL            -- view / edit / export
);
-- 行级数据权限：某角色在某数据集上可见的列值集合
CREATE TABLE IF NOT EXISTS rbi_data_scope (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_id BIGINT NOT NULL,
  dataset_id BIGINT NOT NULL,
  column_name VARCHAR(64) NOT NULL,
  scope_value VARCHAR(256),
  scope_type VARCHAR(16) DEFAULT 'values',  -- values：列值白名单；expr：受限表达式
  filter_expr VARCHAR(512),                 -- expr 模式的过滤条件（如 amount < 10000）
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
-- 列级权限与脱敏：hidden / mask_phone / mask_idcard / mask_name / mask_email / mask_all
CREATE TABLE IF NOT EXISTS rbi_column_policy (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_id BIGINT NOT NULL,
  dataset_id BIGINT NOT NULL,
  column_name VARCHAR(64) NOT NULL,
  policy VARCHAR(32) NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
-- 找回密码令牌（一次性、带过期）
CREATE TABLE IF NOT EXISTS rbi_reset_token (
  token VARCHAR(64) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  expire_at TIMESTAMP NOT NULL,
  used INT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS rbi_schedule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128),
  report_id BIGINT,
  cron VARCHAR(64),
  type VARCHAR(32),        -- email / export / refresh
  receiver VARCHAR(256),
  enabled INT DEFAULT 1,
  last_run_time TIMESTAMP,
  next_run_time TIMESTAMP,
  locked_until TIMESTAMP,  -- 多节点防重：租约到期前其它节点不领取
  retry_count INT DEFAULT 0,
  max_retries INT DEFAULT 3,
  last_error VARCHAR(512)
);
CREATE TABLE IF NOT EXISTS rbi_op_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64),
  module VARCHAR(64),
  operation VARCHAR(128),
  ip VARCHAR(64),
  status VARCHAR(16),                  -- SUCCESS / FAIL
  duration_ms BIGINT,
  detail VARCHAR(1024),                -- 参数摘要（已脱敏）
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- M01 数据源
CREATE TABLE IF NOT EXISTS rbi_datasource (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  type VARCHAR(32) NOT NULL,           -- mysql/oracle/sqlserver/postgresql/dm/kingbase/gaussdb/file/api
  driver_class VARCHAR(128),
  jdbc_url VARCHAR(512),
  username VARCHAR(128),
  password_enc VARCHAR(512),           -- 加密存储 M01-04
  file_path VARCHAR(512),
  api_config TEXT,
  remark VARCHAR(256),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- M02 数据集
CREATE TABLE IF NOT EXISTS rbi_dataset (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  datasource_id BIGINT,
  type VARCHAR(32) DEFAULT 'sql',      -- sql / visual / api / file
  sql_text TEXT,
  config_json TEXT,                    -- 可视化数据集/API映射配置
  params_json TEXT,                    -- 数据集参数定义 M02-03
  cache_seconds INT DEFAULT 0,         -- 缓存过期时间 M02-04
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- M03 报表模板 + 版本
CREATE TABLE IF NOT EXISTS rbi_report (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  directory_id BIGINT,
  dataset_id BIGINT,
  layout_json TEXT,                    -- 单元格布局定义（含扩展/父格/样式/公式）
  params_json TEXT,                    -- 报表参数
  status INT DEFAULT 1,
  current_version INT DEFAULT 1,
  create_by VARCHAR(64),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS rbi_report_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  report_id BIGINT NOT NULL,
  version INT NOT NULL,
  layout_json TEXT,
  remark VARCHAR(256),
  create_by VARCHAR(64),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- M04 参数控件定义（可复用）
CREATE TABLE IF NOT EXISTS rbi_param (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128),
  code VARCHAR(64) NOT NULL UNIQUE,
  type VARCHAR(32),                    -- text/select/multiselect/date/tree
  options_json TEXT,
  default_value VARCHAR(256),
  linked_param VARCHAR(64),            -- 联动依赖参数编码 M04-03
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- M05 图表 / 仪表板
CREATE TABLE IF NOT EXISTS rbi_chart (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128),
  type VARCHAR(32),                    -- bar/line/pie/area/scatter/gauge/...
  dataset_id BIGINT,
  config_json TEXT,                    -- 维度/度量/样式/联动配置
  dashboard_id BIGINT,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS rbi_dashboard (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128),
  layout_json TEXT,                    -- 自由拖拽布局 M05-05
  background VARCHAR(256),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- M06 填报
CREATE TABLE IF NOT EXISTS rbi_form (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128),
  table_name VARCHAR(128),             -- 目标业务表
  fields_json TEXT,                    -- 字段映射/校验规则 M06-01/02
  dataset_id BIGINT,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- M07 自助分析配置
CREATE TABLE IF NOT EXISTS rbi_analysis (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128),
  dataset_id BIGINT,
  config_json TEXT,                    -- 行/列/值/聚合/过滤/排序/快速计算
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- M08 指标管理
CREATE TABLE IF NOT EXISTS rbi_metric (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128),
  code VARCHAR(64) NOT NULL UNIQUE,
  type VARCHAR(32),                    -- atomic / derived
  expression VARCHAR(512),
  dataset_id BIGINT,
  unit VARCHAR(32),
  caliber VARCHAR(512),                -- 口径说明
  lineage_json TEXT,                   -- 血缘 M08-03
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
