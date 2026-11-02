-- 期初导入对账：记住"这一批应该导入多少"，之后可以随时与系统里的实际值对比。
CREATE TABLE migration.opening_row (
  tenant_id uuid NOT NULL,
  school_id uuid NOT NULL,
  batch_id varchar(64) NOT NULL,
  student_no varchar(20) NOT NULL,
  expected_amount numeric(14,2) NOT NULL DEFAULT 0,
  expected_hours numeric(10,2) NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL CHECK(status IN ('OK','SKIPPED','FAILED','READY')),
  detail varchar(300),
  loaded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,batch_id,student_no)
);
CREATE INDEX opening_row_batch ON migration.opening_row(tenant_id,school_id,batch_id);

ALTER TABLE migration.opening_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE migration.opening_row FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON migration.opening_row
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT,UPDATE,DELETE ON migration.opening_row TO ts_app;
