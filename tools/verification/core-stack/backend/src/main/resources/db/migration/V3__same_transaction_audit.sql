-- 验证成功动作与结果同事务，而非依赖 HTTP 日志或提交后内存回调。
ALTER TABLE probe_draft ADD CONSTRAINT probe_draft_tenant_id UNIQUE (tenant_id, id);
CREATE TABLE probe_audit (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  actor varchar(64) NOT NULL,
  action varchar(64) NOT NULL,
  object_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, object_id) REFERENCES probe_draft(tenant_id, id)
);
ALTER TABLE probe_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE probe_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY probe_audit_tenant ON probe_audit
  USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON probe_audit TO probe_app;
