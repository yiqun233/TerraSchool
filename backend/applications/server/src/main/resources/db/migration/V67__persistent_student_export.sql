CREATE TABLE infra.student_export_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  owner_account_id uuid NOT NULL,
  school_id uuid NOT NULL,
  principal_name text NOT NULL,
  request_id uuid NOT NULL,
  request_hash varchar(64) NOT NULL,
  status varchar(16) NOT NULL CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED','CANCELLED','EXPIRED')),
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  manual_retry_count integer NOT NULL DEFAULT 0 CHECK (manual_retry_count >= 0),
  run_token uuid,
  lease_until timestamptz,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  content bytea,
  exposed_fields text NOT NULL DEFAULT '',
  row_count integer,
  error_code varchar(64),
  created_at timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz,
  finished_at timestamptz,
  UNIQUE (tenant_id, owner_account_id, request_id),
  CHECK (status <> 'COMPLETED' OR content IS NOT NULL),
  CHECK (status <> 'COMPLETED' OR row_count IS NOT NULL)
);
CREATE INDEX student_export_task_pending_idx
  ON infra.student_export_task (next_attempt_at, created_at, lease_until)
  WHERE status IN ('PENDING','RUNNING');
CREATE INDEX student_export_task_expiry_idx
  ON infra.student_export_task (expires_at)
  WHERE status = 'COMPLETED';

ALTER TABLE infra.student_export_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE infra.student_export_task FORCE ROW LEVEL SECURITY;
CREATE POLICY student_export_task_tenant ON infra.student_export_task
  USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
GRANT SELECT,INSERT,UPDATE ON infra.student_export_task TO ts_app;
