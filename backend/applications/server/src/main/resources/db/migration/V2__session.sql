-- 表结构依据 Spring Session 4.1.1 的 PostgreSQL schema；通过 Flyway 管理。
-- 会话使用独立基础设施身份，不给普通业务角色绕过租户策略的权限。
CREATE SCHEMA infra;
CREATE TABLE infra.spring_session (
  primary_id char(36) NOT NULL PRIMARY KEY,
  session_id char(36) NOT NULL,
  creation_time bigint NOT NULL,
  last_access_time bigint NOT NULL,
  max_inactive_interval integer NOT NULL,
  expiry_time bigint NOT NULL,
  principal_name varchar(100)
);
CREATE UNIQUE INDEX spring_session_ix1 ON infra.spring_session(session_id);
CREATE INDEX spring_session_ix2 ON infra.spring_session(expiry_time);
CREATE INDEX spring_session_ix3 ON infra.spring_session(principal_name);
CREATE TABLE infra.spring_session_attributes (
  session_primary_id char(36) NOT NULL REFERENCES infra.spring_session(primary_id) ON DELETE CASCADE,
  attribute_name varchar(200) NOT NULL,
  attribute_bytes bytea NOT NULL,
  PRIMARY KEY (session_primary_id, attribute_name)
);
GRANT USAGE ON SCHEMA infra TO ts_session;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA infra TO ts_session;
