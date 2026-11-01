-- 旧系统教师资质为可编辑简介；与教师主体共用版本，旧数据不受影响。
ALTER TABLE organization.teacher ADD COLUMN qualification text NOT NULL DEFAULT ''
 CHECK (length(qualification) <= 10000);
GRANT UPDATE(qualification) ON organization.teacher TO ts_app;
ALTER TABLE organization.unit ADD COLUMN active boolean NOT NULL DEFAULT true;
GRANT UPDATE(active) ON organization.unit TO ts_app;
ALTER TABLE organization.employee ADD COLUMN account_id uuid;
ALTER TABLE organization.employee ADD CONSTRAINT employee_account_same_tenant
 FOREIGN KEY (tenant_id,account_id) REFERENCES identity.account(tenant_id,id);
ALTER TABLE organization.employee ADD CONSTRAINT employee_account_unique UNIQUE (tenant_id,account_id);
GRANT UPDATE(account_id) ON organization.employee TO ts_app;
ALTER TABLE identity.role_action DROP CONSTRAINT role_action_action_check;
ALTER TABLE identity.role_action ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN'));
ALTER TABLE identity.account_permission DROP CONSTRAINT account_permission_action_check;
ALTER TABLE identity.account_permission ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN'));
CREATE TABLE organization.personnel_profile (
 tenant_id uuid NOT NULL, school_id uuid NOT NULL, employee_id uuid PRIMARY KEY,
 profile jsonb NOT NULL, FOREIGN KEY(tenant_id,school_id,employee_id) REFERENCES organization.employee(tenant_id,school_id,id)
);
ALTER TABLE organization.personnel_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization.personnel_profile FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON organization.personnel_profile
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT,UPDATE ON organization.personnel_profile TO ts_app;
ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE','ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE','ACCOUNT_CREATE','ACCOUNT_STATUS','ACCOUNT_PASSWORD_RESET','ACCOUNT_SESSIONS_REVOKE','ORGANIZATION_SCHOOL_CREATE','ORGANIZATION_SCHOOL_UPDATE','EMPLOYEE_CREATE','EMPLOYEE_UPDATE','TEACHER_CREATE','TEACHER_UPDATE','ROOM_CREATE','ROOM_UPDATE','TEACHER_QUALIFICATION_UPDATE','EMPLOYEE_ACCOUNT_BIND','PERSONNEL_UPDATE'));
