CREATE TABLE identity.permission_role (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL REFERENCES identity.tenant(id),
  code varchar(80) NOT NULL,
  name varchar(100) NOT NULL CHECK(length(trim(name))>0),
  active boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,code)
);
CREATE TABLE identity.role_action (
  tenant_id uuid NOT NULL,
  role_id uuid NOT NULL,
  action varchar(40) NOT NULL CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN')),
  effect varchar(5) NOT NULL CHECK(effect IN ('ALLOW','DENY')),
  PRIMARY KEY(role_id,action,effect),
  FOREIGN KEY(tenant_id,role_id) REFERENCES identity.permission_role(tenant_id,id)
);
CREATE TABLE identity.account_role (
  tenant_id uuid NOT NULL,
  account_id uuid NOT NULL,
  role_id uuid NOT NULL,
  school_id uuid NOT NULL,
  school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),
  PRIMARY KEY(account_id,role_id,school_id),
  FOREIGN KEY(tenant_id,account_id) REFERENCES identity.account(tenant_id,id),
  FOREIGN KEY(tenant_id,role_id) REFERENCES identity.permission_role(tenant_id,id),
  FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind)
);
CREATE TABLE identity.account_permission (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  account_id uuid NOT NULL,
  action varchar(40) NOT NULL CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN')),
  -- NULL表示功能级调整，不表示所有学校。数据范围仍需明确的学校ALLOW。
  school_id uuid,
  school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),
  effect varchar(5) NOT NULL CHECK(effect IN ('ALLOW','DENY')),
  UNIQUE NULLS NOT DISTINCT(tenant_id,account_id,action,school_id,effect),
  FOREIGN KEY(tenant_id,account_id) REFERENCES identity.account(tenant_id,id),
  FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind)
);
-- 旧功能开关和范围显式转换：关闭视为功能DENY，开启但无学校仍是空范围。
INSERT INTO identity.account_permission(tenant_id,account_id,action,effect)
 SELECT tenant_id,id,'ORGANIZATION_READ',CASE WHEN organization_read THEN 'ALLOW' ELSE 'DENY' END
 FROM identity.account;
INSERT INTO identity.account_permission(tenant_id,account_id,action,school_id,effect)
 SELECT s.tenant_id,s.account_id,'ORGANIZATION_READ',s.school_id,'ALLOW'
 FROM identity.school_scope s;
INSERT INTO identity.account_permission(tenant_id,account_id,action,school_id,effect)
 SELECT tenant_id,account_id,'ORGANIZATION_MAINTAIN',school_id,effect
 FROM identity.organization_maintenance_permission;
DROP TABLE identity.organization_maintenance_permission;
DROP TABLE identity.school_scope;
ALTER TABLE identity.account DROP COLUMN organization_read;
GRANT SELECT ON identity.permission_role,identity.role_action,identity.account_role,identity.account_permission TO ts_identity;
