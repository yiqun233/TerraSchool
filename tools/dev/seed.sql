-- 只由本地脚本对带本工程专属标签的开发库执行，不属于产品迁移。
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE SCHEMA IF NOT EXISTS dev;
CREATE TABLE IF NOT EXISTS dev.seed_history (
  seed_id text PRIMARY KEY,
  source text NOT NULL,
  applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
DO $seed$
DECLARE
  tenant uuid := uuidv7();
  school_a uuid := uuidv7();
  school_b uuid := uuidv7();
  maintainer uuid := uuidv7();
  reader uuid := uuidv7();
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-demo-v1',0));
  IF EXISTS (SELECT 1 FROM dev.seed_history WHERE seed_id='LOCAL-DEMO-001') THEN
    RETURN;
  END IF;
  INSERT INTO identity.tenant(id,name) VALUES (tenant,'本地演示机构');
  INSERT INTO organization.unit(id,tenant_id,school_id,kind,name) VALUES
    (school_a,tenant,school_a,'SCHOOL','演示学校（总部）'),
    (school_b,tenant,school_b,'SCHOOL','演示学校（分校）');
  INSERT INTO organization.unit(tenant_id,school_id,kind,name) VALUES
    (tenant,school_a,'CAMPUS','总部校区'),
    (tenant,school_a,'DEPARTMENT','总部教务部'),
    (tenant,school_b,'CAMPUS','分校校区'),
    (tenant,school_b,'DEPARTMENT','分校教务部');
  INSERT INTO identity.account(id,tenant_id,login,password_hash,organization_read) VALUES
    (maintainer,tenant,'local-admin','{bcrypt}' || crypt(@@LOGIN_PASSWORD@@,gen_salt('bf',12)),true),
    (reader,tenant,'local-reader','{bcrypt}' || crypt(@@LOGIN_PASSWORD@@,gen_salt('bf',12)),true);
  INSERT INTO identity.school_scope(account_id,tenant_id,school_id) VALUES
    (maintainer,tenant,school_a),(maintainer,tenant,school_b),(reader,tenant,school_a);
  INSERT INTO identity.organization_maintenance_permission(tenant_id,account_id,school_id,effect)
    VALUES (tenant,maintainer,school_a,'ALLOW'),(tenant,maintainer,school_b,'ALLOW');
  INSERT INTO dev.seed_history(seed_id,source)
    VALUES ('LOCAL-DEMO-001','tools/dev/seed.sql：合成演示资料，非旧库迁移或正式管理员');
END;
$seed$;
