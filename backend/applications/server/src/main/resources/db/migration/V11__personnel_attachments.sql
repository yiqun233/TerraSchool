CREATE TABLE organization.personnel_attachment (
 id uuid PRIMARY KEY DEFAULT uuidv7(),tenant_id uuid NOT NULL,school_id uuid NOT NULL,employee_id uuid NOT NULL,
 actor_id uuid NOT NULL,request_id uuid NOT NULL,object_key text NOT NULL,original_name varchar(200) NOT NULL,
 content_size bigint NOT NULL CHECK(content_size BETWEEN 1 AND 5242880), sha256 varchar(64) NOT NULL,
 active boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,actor_id,request_id), UNIQUE(object_key),
 FOREIGN KEY(tenant_id,school_id,employee_id) REFERENCES organization.employee(tenant_id,school_id,id)
);
ALTER TABLE organization.personnel_attachment ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization.personnel_attachment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON organization.personnel_attachment
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT ON organization.personnel_attachment TO ts_app;
GRANT UPDATE(active,version) ON organization.personnel_attachment TO ts_app;
-- 先持久化上传预留；失败或进程中断后仍可安全定位孤儿对象。
CREATE TABLE organization.personnel_upload_pending (
 tenant_id uuid NOT NULL, actor_id uuid NOT NULL, request_id uuid NOT NULL, employee_id uuid NOT NULL,
 object_key text NOT NULL, original_name varchar(200) NOT NULL, sha256 varchar(64) NOT NULL,
 touched_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(tenant_id,actor_id,request_id)
);
ALTER TABLE organization.personnel_upload_pending ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization.personnel_upload_pending FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON organization.personnel_upload_pending
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT,DELETE ON organization.personnel_upload_pending TO ts_app;
GRANT UPDATE(touched_at) ON organization.personnel_upload_pending TO ts_app;
ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE','ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE','ACCOUNT_CREATE','ACCOUNT_STATUS','ACCOUNT_PASSWORD_RESET','ACCOUNT_SESSIONS_REVOKE','ORGANIZATION_SCHOOL_CREATE','ORGANIZATION_SCHOOL_UPDATE','EMPLOYEE_CREATE','EMPLOYEE_UPDATE','TEACHER_CREATE','TEACHER_UPDATE','ROOM_CREATE','ROOM_UPDATE','TEACHER_QUALIFICATION_UPDATE','EMPLOYEE_ACCOUNT_BIND','PERSONNEL_UPDATE','PERSONNEL_ATTACHMENT_CREATE','PERSONNEL_ATTACHMENT_READ','PERSONNEL_ATTACHMENT_REMOVE'));
