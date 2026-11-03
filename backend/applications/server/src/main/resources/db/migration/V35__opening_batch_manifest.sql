-- 完整源清单先于任何逐行记账持久化；行号保留重复及无效学员号。
-- 旧期初消费的业务日期无法反推，保留 NULL 供人工核验。
ALTER TABLE teaching.consumption ADD COLUMN opening_occurred_on date;
CREATE TABLE migration.opening_batch (
 tenant_id uuid NOT NULL, school_id uuid NOT NULL, batch_id varchar(64) NOT NULL,
 occurred_on date NOT NULL, reason varchar(200), row_count integer NOT NULL CHECK (row_count BETWEEN 1 AND 5000),
 PRIMARY KEY (tenant_id, school_id, batch_id)
);
CREATE TABLE migration.opening_batch_row (
 tenant_id uuid NOT NULL, school_id uuid NOT NULL, batch_id varchar(64) NOT NULL,
 row_no integer NOT NULL CHECK (row_no > 0), row_is_null boolean NOT NULL,
 student_no text, amount text, hours text, consumed_amount text,
 status varchar(12) NOT NULL CHECK (status IN ('PENDING','FAILED','OK')),
 detail text,
 PRIMARY KEY (tenant_id, school_id, batch_id, row_no),
 FOREIGN KEY (tenant_id, school_id, batch_id) REFERENCES migration.opening_batch(tenant_id, school_id, batch_id)
);
ALTER TABLE migration.opening_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE migration.opening_batch FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON migration.opening_batch
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE migration.opening_batch_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE migration.opening_batch_row FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON migration.opening_batch_row
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT, INSERT ON migration.opening_batch TO ts_app;
GRANT SELECT, INSERT, UPDATE(status,detail) ON migration.opening_batch_row TO ts_app;
