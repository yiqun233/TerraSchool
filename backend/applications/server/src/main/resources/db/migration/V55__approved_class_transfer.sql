-- REV-012: retain each class transfer separately from the active membership snapshot.
CREATE TABLE teaching.class_transfer (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  enrollment_id uuid NOT NULL,
  from_class_id uuid NOT NULL,
  to_class_id uuid NOT NULL,
  approval_id uuid,
  reason varchar(200) NOT NULL CHECK(length(trim(reason)) > 0),
  actor_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  CHECK(from_class_id <> to_class_id),
  FOREIGN KEY(tenant_id,enrollment_id) REFERENCES enrollment.enrollment(tenant_id,id),
  FOREIGN KEY(tenant_id,from_class_id) REFERENCES teaching.class(tenant_id,id),
  FOREIGN KEY(tenant_id,to_class_id) REFERENCES teaching.class(tenant_id,id),
  FOREIGN KEY(tenant_id,approval_id) REFERENCES approval.request(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES identity.account(tenant_id,id)
);
CREATE INDEX class_transfer_enrollment ON teaching.class_transfer(tenant_id,enrollment_id,created_at,id);
ALTER TABLE teaching.class_transfer ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching.class_transfer FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON teaching.class_transfer
  USING (tenant_id = current_setting('app.tenant_id',true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id',true)::uuid);
GRANT SELECT,INSERT ON teaching.class_transfer TO ts_app;
