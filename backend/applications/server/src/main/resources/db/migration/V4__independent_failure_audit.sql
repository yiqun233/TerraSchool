ALTER TABLE audit.business_event ADD COLUMN trace_id uuid;
CREATE INDEX business_event_trace ON audit.business_event(trace_id);

-- 失败记录不关联可回滚的业务行；未知主体不能自行指定租户。
CREATE TABLE audit.operation_failure (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  attempt_id uuid NOT NULL UNIQUE,
  trace_id uuid NOT NULL,
  tenant_id uuid,
  actor_id uuid,
  action varchar(40) NOT NULL CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME')),
  source varchar(16) NOT NULL CHECK(source IN ('HTTP','APPLICATION')),
  result varchar(16) NOT NULL CHECK(result IN ('REJECTED','FAILED')),
  code varchar(64) NOT NULL,
  resource_id uuid,
  happened_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK((tenant_id IS NULL) = (actor_id IS NULL))
);
CREATE INDEX operation_failure_tenant_time ON audit.operation_failure(tenant_id,happened_at,id);
CREATE INDEX operation_failure_trace ON audit.operation_failure(trace_id);
ALTER TABLE audit.operation_failure ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.operation_failure FORCE ROW LEVEL SECURITY;
CREATE POLICY failure_append ON audit.operation_failure FOR INSERT TO ts_audit WITH CHECK(true);
CREATE POLICY failure_tenant_read ON audit.operation_failure FOR SELECT TO ts_app
  USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT USAGE ON SCHEMA audit TO ts_audit;
GRANT INSERT ON audit.operation_failure TO ts_audit;
GRANT SELECT ON audit.operation_failure TO ts_app;
