-- REV-019：平台运营身份（DEC-024）。
--
-- 平台账号与租户账号物理隔离：独立的 schema、独立的表、独立的数据库角色 ts_platform。
-- 平台角色只拿到 platform schema 的权限和 identity.tenant 的只读，
-- 任何 teaching/enrollment/funds/archive 表都没有授权——「平台不看租户业务数据」
-- 由数据库保证，而不是靠应用层自觉。

CREATE SCHEMA platform;

CREATE TABLE platform.operator (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  login varchar(60) NOT NULL UNIQUE CHECK(length(trim(login)) > 0),
  password_hash varchar(255) NOT NULL,
  display_name varchar(100) NOT NULL CHECK(length(trim(display_name)) > 0),
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_login_at timestamptz
);

-- 平台动作目录：与租户权限目录分开，不共享取值。
CREATE TABLE platform.operator_permission (
  operator_id uuid NOT NULL REFERENCES platform.operator(id),
  action varchar(60) NOT NULL CHECK(action IN (
    'TENANT_READ','TENANT_CREATE','TENANT_SUSPEND','TENANT_RESUME','PROVISIONING_READ')),
  effect varchar(5) NOT NULL CHECK(effect IN ('ALLOW','DENY')),
  PRIMARY KEY (operator_id, action)
);

-- 平台会话：独立表、独立 Cookie，不复用租户的 Spring Session 表。
-- 只存 token 的 SHA-256，泄露库也不等于能直接登录。
CREATE TABLE platform.operator_session (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  operator_id uuid NOT NULL REFERENCES platform.operator(id),
  token_hash char(64) NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at timestamptz NOT NULL,
  last_seen_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX operator_session_expiry ON platform.operator_session(expires_at);

-- 平台审计：独立表，不写 audit.*（那是租户审计，租户管理员可以查自己的）。
CREATE TABLE platform.audit_event (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  operator_id uuid NOT NULL,
  action varchar(60) NOT NULL,
  tenant_id uuid,
  changed_fields varchar(400) NOT NULL DEFAULT '',
  trace_id uuid NOT NULL,
  happened_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX audit_event_tenant ON platform.audit_event(tenant_id, happened_at);
CREATE INDEX audit_event_operator ON platform.audit_event(operator_id, happened_at);

-- 平台角色：无超级用户、无建库建角色、不能绕过 RLS。
GRANT USAGE ON SCHEMA platform TO ts_platform;
GRANT SELECT ON platform.operator TO ts_platform;
GRANT SELECT ON platform.operator_permission TO ts_platform;
GRANT SELECT, INSERT, UPDATE, DELETE ON platform.operator_session TO ts_platform;
GRANT SELECT, INSERT ON platform.audit_event TO ts_platform;

-- 开通与列表要判断租户是否存在、是否启用；只读这一张，且不含任何业务数据。
-- 表级 SELECT 之外还必须给 schema USAGE，否则连解析表名都会被拒绝。
GRANT USAGE ON SCHEMA identity TO ts_platform;
GRANT SELECT ON identity.tenant TO ts_platform;
