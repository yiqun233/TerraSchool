ALTER TABLE identity.account ADD COLUMN authorization_version bigint NOT NULL DEFAULT 0;
ALTER TABLE identity.role_action DROP CONSTRAINT role_action_action_check;
ALTER TABLE identity.role_action ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE'));
ALTER TABLE identity.account_permission DROP CONSTRAINT account_permission_action_check;
ALTER TABLE identity.account_permission ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE'));

-- 身份读取仍为受限只读基础设施；授权修改使用普通业务事务及强制租户RLS。
ALTER TABLE identity.tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY identity_read ON identity.tenant FOR SELECT TO ts_identity USING(true);
CREATE POLICY migration_access ON identity.tenant TO CURRENT_USER USING(true) WITH CHECK(true);
CREATE POLICY tenant_access ON identity.tenant TO ts_app
 USING(id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(id=nullif(current_setting('app.tenant_id',true),'')::uuid);
DO $policies$
DECLARE tab text;
BEGIN
 FOR tab IN SELECT unnest(ARRAY['account','permission_role','role_action','account_role','account_permission']) LOOP
  EXECUTE format('ALTER TABLE identity.%I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE identity.%I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY identity_read ON identity.%I FOR SELECT TO ts_identity USING(true)',tab);
  EXECUTE format('CREATE POLICY migration_access ON identity.%I TO %I USING(true) WITH CHECK(true)',tab,current_user);
  EXECUTE format('CREATE POLICY tenant_access ON identity.%I TO ts_app USING(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
 END LOOP;
END;
$policies$;
GRANT USAGE ON SCHEMA identity TO ts_app;
GRANT SELECT(id,active) ON identity.tenant TO ts_app;
GRANT SELECT(id,tenant_id,login,active,authorization_version) ON identity.account TO ts_app;
GRANT UPDATE(authorization_version) ON identity.account TO ts_app;
GRANT SELECT ON identity.permission_role,identity.role_action,identity.account_role,identity.account_permission TO ts_app;
GRANT INSERT,DELETE ON identity.account_role,identity.account_permission TO ts_app;

CREATE TABLE audit.authorization_event (
 id uuid PRIMARY KEY DEFAULT uuidv7(), tenant_id uuid NOT NULL, actor_id uuid NOT NULL,
 target_account_id uuid NOT NULL, previous_version bigint NOT NULL, next_version bigint NOT NULL,
 before_rules jsonb NOT NULL, after_rules jsonb NOT NULL, trace_id uuid NOT NULL,
 happened_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE audit.authorization_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.authorization_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON audit.authorization_event
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT INSERT ON audit.authorization_event TO ts_app;
ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE'));
