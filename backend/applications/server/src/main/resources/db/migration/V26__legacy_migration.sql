-- 旧库迁移：暂存区、导入台账与运行记录。
-- 旧库只有结构、没有数据快照；这里先把"装载 → 干跑 → 确认"的骨架建好，
-- 真实快照到位后按同一契约灌入暂存区即可跑对账。
CREATE SCHEMA migration;

CREATE TABLE migration.run (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  school_id uuid NOT NULL,
  mode varchar(16) NOT NULL CHECK(mode IN ('DRY_RUN','COMMIT')),
  actor_id uuid NOT NULL,
  started_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  students_staged integer NOT NULL DEFAULT 0,
  students_imported integer NOT NULL DEFAULT 0,
  receipts_staged integer NOT NULL DEFAULT 0,
  receipts_imported integer NOT NULL DEFAULT 0,
  issues jsonb NOT NULL DEFAULT '[]'::jsonb
);

-- 学员暂存：一行对应旧库一条学员档案（含家长联系人）。
CREATE TABLE migration.legacy_student (
  tenant_id uuid NOT NULL,
  school_id uuid NOT NULL,
  legacy_id varchar(64) NOT NULL,
  student_no varchar(20) NOT NULL,
  name varchar(50) NOT NULL,
  sex varchar(10) NOT NULL,
  birthday date,
  cert_code varchar(40),
  phone varchar(30),
  contact_name varchar(50),
  contact_phone varchar(30),
  loaded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,legacy_id)
);

-- 收款暂存：按旧库收款流水，用学员号关联。
CREATE TABLE migration.legacy_receipt (
  tenant_id uuid NOT NULL,
  school_id uuid NOT NULL,
  legacy_id varchar(64) NOT NULL,
  student_no varchar(20) NOT NULL,
  amount numeric(14,2) NOT NULL CHECK(amount > 0),
  method varchar(16) NOT NULL,
  occurred_on date NOT NULL,
  loaded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,legacy_id)
);

-- 导入台账：同一旧主键只导入一次，重复确认幂等。
CREATE TABLE migration.import_log (
  tenant_id uuid NOT NULL,
  kind varchar(16) NOT NULL CHECK(kind IN ('STUDENT','RECEIPT')),
  legacy_id varchar(64) NOT NULL,
  target_id uuid NOT NULL,
  imported_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,kind,legacy_id)
);

ALTER TABLE migration.run ENABLE ROW LEVEL SECURITY;
ALTER TABLE migration.run FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON migration.run
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE migration.legacy_student ENABLE ROW LEVEL SECURITY;
ALTER TABLE migration.legacy_student FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON migration.legacy_student
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE migration.legacy_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE migration.legacy_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON migration.legacy_receipt
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE migration.import_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE migration.import_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON migration.import_log
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

GRANT USAGE ON SCHEMA migration TO ts_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON migration.run TO ts_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON migration.legacy_student TO ts_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON migration.legacy_receipt TO ts_app;
GRANT SELECT,INSERT ON migration.import_log TO ts_app;
