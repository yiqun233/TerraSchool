-- 旧 opening_row 没有事实来源映射，保留供历史查询；新批次记录不可变的逐行来源和目标事实。
CREATE TABLE migration.opening_fact (
  tenant_id uuid NOT NULL,
  school_id uuid NOT NULL,
  batch_id varchar(64) NOT NULL,
  student_no varchar(20) NOT NULL,
  student_id uuid NOT NULL,
  enrollment_id uuid,
  amount numeric(14,2) NOT NULL,
  hours numeric(10,2) NOT NULL CHECK (hours >= 0),
  consumed_amount numeric(14,2) NOT NULL CHECK (consumed_amount >= 0),
  occurred_on date NOT NULL,
  reason varchar(200),
  account_entry_id uuid,
  consumption_id uuid,
  PRIMARY KEY (tenant_id, school_id, batch_id, student_no)
);
ALTER TABLE migration.opening_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE migration.opening_fact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON migration.opening_fact
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT, INSERT ON migration.opening_fact TO ts_app;
