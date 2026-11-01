-- 模板定义与账号分配变化共用模板version；code创建后不允许普通应用修改。
GRANT INSERT ON identity.permission_role TO ts_app;
GRANT UPDATE(name,active,version) ON identity.permission_role TO ts_app;
GRANT INSERT,DELETE ON identity.role_action TO ts_app;
CREATE INDEX account_role_template_usage ON identity.account_role(tenant_id,role_id,account_id);

CREATE TABLE identity.role_creation_request (
 tenant_id uuid NOT NULL, actor_id uuid NOT NULL, request_id uuid NOT NULL,
 role_id uuid NOT NULL, input_json jsonb NOT NULL, created_role jsonb NOT NULL,
 PRIMARY KEY(tenant_id,actor_id,request_id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES identity.account(tenant_id,id),
 FOREIGN KEY(tenant_id,role_id) REFERENCES identity.permission_role(tenant_id,id)
);
ALTER TABLE identity.role_creation_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.role_creation_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON identity.role_creation_request
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT ON identity.role_creation_request TO ts_app;

CREATE TABLE audit.role_template_event (
 id uuid PRIMARY KEY DEFAULT uuidv7(), tenant_id uuid NOT NULL, actor_id uuid NOT NULL,
 role_id uuid NOT NULL, action varchar(40) NOT NULL CHECK(action IN ('ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE')),
 before_template jsonb, after_template jsonb NOT NULL, affected_accounts integer NOT NULL,
 trace_id uuid NOT NULL, happened_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE audit.role_template_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.role_template_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON audit.role_template_event
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT INSERT ON audit.role_template_event TO ts_app;
ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE','ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE'));
