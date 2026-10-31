-- 仅为本批维护动作增加学校范围授权；完整角色管理另行实现。
CREATE TABLE identity.organization_maintenance_permission (
  tenant_id uuid NOT NULL,
  account_id uuid NOT NULL,
  school_id uuid NOT NULL,
  school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK (school_kind='SCHOOL'),
  effect varchar(5) NOT NULL CHECK (effect IN ('ALLOW','DENY')),
  PRIMARY KEY (account_id,school_id,effect),
  FOREIGN KEY (tenant_id,account_id) REFERENCES identity.account(tenant_id,id),
  FOREIGN KEY (tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind)
);
GRANT SELECT ON identity.organization_maintenance_permission TO ts_identity;

CREATE TABLE organization.creation_request (
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  request_id uuid NOT NULL,
  school_id uuid NOT NULL,
  unit_id uuid NOT NULL,
  kind varchar(20) NOT NULL,
  name varchar(100) NOT NULL,
  PRIMARY KEY (tenant_id,actor_id,request_id),
  FOREIGN KEY (tenant_id,unit_id) REFERENCES organization.unit(tenant_id,id)
);
ALTER TABLE organization.creation_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization.creation_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON organization.creation_request
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT ON organization.creation_request TO ts_app;
GRANT INSERT ON organization.unit TO ts_app;
GRANT UPDATE(name,version) ON organization.unit TO ts_app;

CREATE SCHEMA audit;
CREATE TABLE audit.business_event (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  action varchar(80) NOT NULL,
  resource_id uuid NOT NULL,
  school_id uuid NOT NULL,
  old_name varchar(100),
  new_name varchar(100) NOT NULL,
  happened_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE audit.business_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.business_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON audit.business_event
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT USAGE ON SCHEMA audit TO ts_app;
-- 应用只追加审计，不开放修改、删除或普通查询接口。
GRANT INSERT ON audit.business_event TO ts_app;
