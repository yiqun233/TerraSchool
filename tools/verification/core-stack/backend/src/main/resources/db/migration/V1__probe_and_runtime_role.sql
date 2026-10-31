-- 仅用于 run.py 创建的临时 PostgreSQL；不是正式业务表或生产角色定义。
CREATE ROLE probe_app LOGIN PASSWORD '${appPassword}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
CREATE TABLE probe_draft (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  amount numeric(24,6) NOT NULL CHECK (amount > 0),
  business_date date NOT NULL,
  happened_at timestamptz NOT NULL,
  note text,
  version integer NOT NULL DEFAULT 0
);
ALTER TABLE probe_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE probe_draft FORCE ROW LEVEL SECURITY;
-- set_config(..., true) 绑定事务实际连接；事务结束后空上下文必须继续拒绝。
CREATE POLICY probe_tenant ON probe_draft
  USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON probe_draft FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO probe_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON probe_draft TO probe_app;
