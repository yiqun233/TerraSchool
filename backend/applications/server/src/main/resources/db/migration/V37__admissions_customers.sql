-- 客户首批仅允许创建人访问；不定义公私海、分配、合并或管理员扩大范围。
CREATE TABLE admissions.customer (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES identity.tenant(id), school_id uuid NOT NULL,
 school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),
 creator_id uuid NOT NULL, name varchar(50) NOT NULL CHECK(length(trim(name))>0),
 phone varchar(30) NOT NULL, normalized_phone varchar(21) NOT NULL,
 channel_id uuid, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind),
 FOREIGN KEY(tenant_id,creator_id) REFERENCES identity.account(tenant_id,id),
 FOREIGN KEY(tenant_id,channel_id) REFERENCES admissions.channel(tenant_id,id)
);
CREATE INDEX customer_owner_school ON admissions.customer(tenant_id,creator_id,school_id,created_at,id);
CREATE INDEX customer_owner_phone ON admissions.customer(tenant_id,creator_id,normalized_phone);
CREATE TABLE admissions.customer_request (
 tenant_id uuid NOT NULL, actor_id uuid NOT NULL, request_id uuid NOT NULL,
 school_id uuid NOT NULL, customer_id uuid, fingerprint varchar(64) NOT NULL,
 status varchar(12) NOT NULL CHECK(status IN ('CREATED','CANCELLED')),
 CHECK((status='CREATED' AND customer_id IS NOT NULL) OR (status='CANCELLED' AND customer_id IS NULL)),
 PRIMARY KEY(tenant_id,actor_id,request_id), FOREIGN KEY(tenant_id,customer_id) REFERENCES admissions.customer(tenant_id,id)
);
-- 重复确认凭证绑定主体、请求、学校和输入摘要，只能一次使用且短期有效。
CREATE TABLE admissions.customer_duplicate_challenge (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, actor_id uuid NOT NULL, school_id uuid NOT NULL,
 request_id uuid NOT NULL, fingerprint varchar(64) NOT NULL,
 expires_at timestamptz NOT NULL DEFAULT (clock_timestamp() + interval '15 minutes'), used_at timestamptz
);
CREATE TABLE audit.customer_event (
 id uuid PRIMARY KEY DEFAULT uuidv7(), tenant_id uuid NOT NULL, actor_id uuid NOT NULL,
 school_id uuid NOT NULL, customer_id uuid, request_id uuid,
 action varchar(20) NOT NULL CHECK(action IN ('CREATED','PHONE_VIEWED','DUPLICATE_DETECTED','CANCELLED')),
 CHECK((action IN ('CREATED','PHONE_VIEWED') AND customer_id IS NOT NULL) OR (action IN ('DUPLICATE_DETECTED','CANCELLED') AND request_id IS NOT NULL)),
 duplicate_confirmed boolean NOT NULL DEFAULT false, trace_id uuid NOT NULL,
 happened_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE admissions.customer ENABLE ROW LEVEL SECURITY;
ALTER TABLE admissions.customer FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON admissions.customer USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE admissions.customer_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE admissions.customer_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON admissions.customer_request USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE admissions.customer_duplicate_challenge ENABLE ROW LEVEL SECURITY;
ALTER TABLE admissions.customer_duplicate_challenge FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON admissions.customer_duplicate_challenge USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE audit.customer_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.customer_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON audit.customer_event USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT ON admissions.customer TO ts_app;
GRANT SELECT,INSERT ON admissions.customer_request TO ts_app;
GRANT SELECT,INSERT,UPDATE(used_at) ON admissions.customer_duplicate_challenge TO ts_app;
GRANT INSERT ON audit.customer_event TO ts_app;
ALTER TABLE identity.role_action DROP CONSTRAINT role_action_action_check;
ALTER TABLE identity.role_action ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN','STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ','PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH','ENROLLMENT_READ','ENROLLMENT_MAINTAIN','ENROLLMENT_APPROVE','FUNDS_READ','FUNDS_RECEIPT','FUNDS_ALLOCATE','TEACHING_READ','TEACHING_MAINTAIN','ATTENDANCE_RECORD','LESSON_CLOSE','REFUND_READ','REFUND_APPLY','REFUND_PAY','APPROVAL_READ','APPROVAL_MANAGE','APPROVAL_DECIDE','PERIOD_READ','PERIOD_LOCK','MIGRATION_MANAGE','CHANNEL_READ','CHANNEL_MAINTAIN','CUSTOMER_READ','CUSTOMER_MAINTAIN','CUSTOMER_PHONE_READ'));

ALTER TABLE identity.account_permission DROP CONSTRAINT account_permission_action_check;
ALTER TABLE identity.account_permission ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN','STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ','PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH','ENROLLMENT_READ','ENROLLMENT_MAINTAIN','ENROLLMENT_APPROVE','FUNDS_READ','FUNDS_RECEIPT','FUNDS_ALLOCATE','TEACHING_READ','TEACHING_MAINTAIN','ATTENDANCE_RECORD','LESSON_CLOSE','REFUND_READ','REFUND_APPLY','REFUND_PAY','APPROVAL_READ','APPROVAL_MANAGE','APPROVAL_DECIDE','PERIOD_READ','PERIOD_LOCK','MIGRATION_MANAGE','CHANNEL_READ','CHANNEL_MAINTAIN','CUSTOMER_READ','CUSTOMER_MAINTAIN','CUSTOMER_PHONE_READ'));
ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE','ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE','ACCOUNT_CREATE','ACCOUNT_STATUS','ACCOUNT_PASSWORD_RESET','ACCOUNT_SESSIONS_REVOKE','ORGANIZATION_SCHOOL_CREATE','ORGANIZATION_SCHOOL_UPDATE','EMPLOYEE_CREATE','EMPLOYEE_UPDATE','TEACHER_CREATE','TEACHER_UPDATE','ROOM_CREATE','ROOM_UPDATE','TEACHER_QUALIFICATION_UPDATE','EMPLOYEE_ACCOUNT_BIND','PERSONNEL_UPDATE','PERSONNEL_ATTACHMENT_CREATE','PERSONNEL_ATTACHMENT_READ','PERSONNEL_ATTACHMENT_REMOVE','EMPLOYEE_EMAIL_UPDATE','PERSONNEL_EXTERNAL_UPDATE','STUDENT_CREATE','STUDENT_UPDATE','STUDENT_SENSITIVE_READ','CURRICULUM_CREATE','CURRICULUM_UPDATE','PRODUCT_CREATE','PRODUCT_UPDATE','PRICE_VERSION_CREATE','PRICE_VERSION_UPDATE','PRICE_VERSION_PUBLISH','PRICE_VERSION_RETIRE','ENROLLMENT_CREATE','ENROLLMENT_APPROVE','ENROLLMENT_DISCOUNT','RECEIPT_CONFIRM','RECEIPT_VOID','ALLOCATION_CREATE','ALLOCATION_RELEASE','CLASS_CREATE','CLASS_MEMBER_ADD','CLASS_CLOSE','LESSON_CREATE','LESSON_PUBLISH','ATTENDANCE_CONFIRM','LESSON_CLOSE','REFUND_HOURS','REFUND_PREPARE','REFUND_PAID','REFUND_CANCEL','APPROVAL_POLICY','APPROVAL_REQUEST','APPROVAL_DECIDE','PERIOD_LOCK','PERIOD_UNLOCK','SCHEDULE_PREVIEW','SCHEDULE_CONFIRM','RESOURCE_BLOCK_CREATE','RESOURCE_BLOCK_DELETE','SCHEDULE_POLICY_UPDATE','MIGRATION_STAGE','MIGRATION_DRY_RUN','MIGRATION_COMMIT','STUDENT_EXPORT','CHANNEL_CREATE','CHANNEL_UPDATE','CUSTOMER_CREATE','CUSTOMER_CANCEL','CUSTOMER_PHONE_VIEW'));
