-- Approval application requests must survive HTTP retries without creating a second request.
CREATE TABLE approval.request_command (
  tenant_id uuid NOT NULL REFERENCES identity.tenant(id),
  actor_id uuid NOT NULL,
  request_id uuid NOT NULL,
  fingerprint char(64) NOT NULL,
  approval_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id,actor_id,request_id),
  UNIQUE (tenant_id,approval_id),
  FOREIGN KEY (tenant_id,actor_id) REFERENCES identity.account(tenant_id,id),
  FOREIGN KEY (tenant_id,approval_id) REFERENCES approval.request(tenant_id,id)
);

ALTER TABLE approval.request_command ENABLE ROW LEVEL SECURITY;
ALTER TABLE approval.request_command FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON approval.request_command
  USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

GRANT SELECT,INSERT ON approval.request_command TO ts_app;
