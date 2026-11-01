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
  INSERT INTO identity.account(id,tenant_id,login,password_hash) VALUES
    (maintainer,tenant,'local-admin','{bcrypt}' || crypt(@@LOGIN_PASSWORD@@,gen_salt('bf',12))),
    (reader,tenant,'local-reader','{bcrypt}' || crypt(@@LOGIN_PASSWORD@@,gen_salt('bf',12)));
  INSERT INTO identity.account_permission(account_id,tenant_id,school_id,action,effect) VALUES
    (maintainer,tenant,school_a,'ORGANIZATION_READ','ALLOW'),(maintainer,tenant,school_b,'ORGANIZATION_READ','ALLOW'),(reader,tenant,school_a,'ORGANIZATION_READ','ALLOW');
  INSERT INTO identity.account_permission(tenant_id,account_id,school_id,action,effect)
    VALUES (tenant,maintainer,school_a,'ORGANIZATION_MAINTAIN','ALLOW'),(tenant,maintainer,school_b,'ORGANIZATION_MAINTAIN','ALLOW');
  INSERT INTO dev.seed_history(seed_id,source)
    VALUES ('LOCAL-DEMO-001','tools/dev/seed.sql：合成演示资料，非旧库迁移或正式管理员');
END;
$seed$;

-- 仅为带归属标记的合成开发环境开通新功能，不属于产品Flyway或默认管理员。
CREATE TABLE IF NOT EXISTS dev.authorization_seed_history (
  seed_id text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
DO $authorization_seed$
DECLARE
  owner_id uuid; tenant_id_value uuid; template_id uuid;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-authorization-v1',0));
  IF EXISTS(SELECT 1 FROM dev.authorization_seed_history WHERE seed_id='LOCAL-AUTHORIZATION-001') THEN RETURN; END IF;
  SELECT a.id,a.tenant_id INTO STRICT owner_id,tenant_id_value
    FROM identity.account a JOIN identity.tenant t ON t.id=a.tenant_id
    WHERE a.login='local-admin' AND t.name='本地演示机构' AND a.active AND t.active;
  -- 不覆盖已有管理规则；之后手工撤销也不会因再次启动恢复。
  IF NOT EXISTS(SELECT 1 FROM identity.account_permission WHERE account_id=owner_id AND action='AUTHORIZATION_MANAGE') THEN
    INSERT INTO identity.account_permission(tenant_id,account_id,action,school_id,effect)
      SELECT tenant_id_value,owner_id,'AUTHORIZATION_MANAGE',id,'ALLOW'
      FROM organization.unit WHERE tenant_id=tenant_id_value AND kind='SCHOOL';
  END IF;
  INSERT INTO identity.permission_role(tenant_id,code,name) VALUES(tenant_id_value,'LOCAL_ORG_READER','演示组织只读')
    ON CONFLICT(tenant_id,code) DO NOTHING RETURNING id INTO template_id;
  IF template_id IS NOT NULL THEN
    INSERT INTO identity.role_action(tenant_id,role_id,action,effect) VALUES(tenant_id_value,template_id,'ORGANIZATION_READ','ALLOW');
  END IF;
  INSERT INTO identity.permission_role(tenant_id,code,name) VALUES(tenant_id_value,'LOCAL_ORG_MAINTAINER','演示组织维护')
    ON CONFLICT(tenant_id,code) DO NOTHING RETURNING id INTO template_id;
  IF template_id IS NOT NULL THEN
    INSERT INTO identity.role_action(tenant_id,role_id,action,effect)
      VALUES(tenant_id_value,template_id,'ORGANIZATION_READ','ALLOW'),(tenant_id_value,template_id,'ORGANIZATION_MAINTAIN','ALLOW');
  END IF;
  INSERT INTO dev.authorization_seed_history(seed_id) VALUES('LOCAL-AUTHORIZATION-001');
END;
$authorization_seed$;

-- 本地合成管理员的一次性资源权限，不覆盖手工调整，不属于生产默认授权。
CREATE TABLE IF NOT EXISTS dev.resource_seed_history(seed_id text PRIMARY KEY,applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP);
DO $resource_seed$
DECLARE owner_id uuid; tenant_id_value uuid; action_name text;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-resources-v1',0));
 IF EXISTS(SELECT 1 FROM dev.resource_seed_history WHERE seed_id='LOCAL-RESOURCES-001') THEN RETURN; END IF;
 SELECT a.id,a.tenant_id INTO STRICT owner_id,tenant_id_value FROM identity.account a JOIN identity.tenant t ON t.id=a.tenant_id
 WHERE a.login='local-admin' AND t.name='本地演示机构' AND a.active AND t.active;
 FOREACH action_name IN ARRAY ARRAY['EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN'] LOOP
  IF NOT EXISTS(SELECT 1 FROM identity.account_permission WHERE account_id=owner_id AND action=action_name) THEN
   INSERT INTO identity.account_permission(tenant_id,account_id,action,school_id,effect)
    SELECT tenant_id_value,owner_id,action_name,id,'ALLOW' FROM organization.unit WHERE tenant_id=tenant_id_value AND kind='SCHOOL';
  END IF;
 END LOOP;
 INSERT INTO dev.resource_seed_history(seed_id) VALUES('LOCAL-RESOURCES-001');
END;
$resource_seed$;

-- 独立人事资料授权仅初始化本机演示管理员，已有手工规则不覆盖。
DO $personnel_seed$
DECLARE owner_id uuid; tenant_id_value uuid; action_name text;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-personnel-v1',0));
 IF EXISTS(SELECT 1 FROM dev.resource_seed_history WHERE seed_id='LOCAL-PERSONNEL-001') THEN RETURN; END IF;
 SELECT a.id,a.tenant_id INTO STRICT owner_id,tenant_id_value FROM identity.account a JOIN identity.tenant t ON t.id=a.tenant_id
 WHERE a.login='local-admin' AND t.name='本地演示机构' AND a.active AND t.active;
 FOREACH action_name IN ARRAY ARRAY['PERSONNEL_READ','PERSONNEL_MAINTAIN'] LOOP
  IF NOT EXISTS(SELECT 1 FROM identity.account_permission WHERE account_id=owner_id AND action=action_name) THEN
   INSERT INTO identity.account_permission(tenant_id,account_id,action,school_id,effect)
    SELECT tenant_id_value,owner_id,action_name,id,'ALLOW' FROM organization.unit WHERE tenant_id=tenant_id_value AND kind='SCHOOL';
  END IF;
 END LOOP;
 INSERT INTO dev.resource_seed_history(seed_id) VALUES('LOCAL-PERSONNEL-001');
END;
$personnel_seed$;
