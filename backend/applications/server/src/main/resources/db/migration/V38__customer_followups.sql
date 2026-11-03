-- 跟进记录只追加，学校和作者与本人客户保持一致。
ALTER TABLE admissions.customer ADD UNIQUE(tenant_id,id,school_id,creator_id);
CREATE TABLE admissions.customer_followup (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, school_id uuid NOT NULL, customer_id uuid NOT NULL,
 author_id uuid NOT NULL, content varchar(1000) NOT NULL CHECK(length(trim(content))>0),
 occurred_on date NOT NULL, next_contact_on date, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,id,customer_id,author_id),
 FOREIGN KEY(tenant_id,customer_id,school_id,author_id) REFERENCES admissions.customer(tenant_id,id,school_id,creator_id)
);
CREATE INDEX customer_followup_owner ON admissions.customer_followup(tenant_id,customer_id,author_id,created_at DESC,id DESC);
CREATE TABLE admissions.customer_followup_request (
 tenant_id uuid NOT NULL, actor_id uuid NOT NULL, request_id uuid NOT NULL, customer_id uuid NOT NULL,
 followup_id uuid NOT NULL, fingerprint varchar(64) NOT NULL,
 PRIMARY KEY(tenant_id,actor_id,request_id),
 FOREIGN KEY(tenant_id,followup_id,customer_id,actor_id) REFERENCES admissions.customer_followup(tenant_id,id,customer_id,author_id)
);
ALTER TABLE admissions.customer_followup ENABLE ROW LEVEL SECURITY;
ALTER TABLE admissions.customer_followup FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON admissions.customer_followup USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE admissions.customer_followup_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE admissions.customer_followup_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON admissions.customer_followup_request USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT ON admissions.customer_followup, admissions.customer_followup_request TO ts_app;
ALTER TABLE audit.customer_event DROP CONSTRAINT customer_event_action_check;
ALTER TABLE audit.customer_event DROP CONSTRAINT customer_event_check;
ALTER TABLE audit.customer_event ADD CHECK(action IN ('CREATED','PHONE_VIEWED','DUPLICATE_DETECTED','CANCELLED','FOLLOWUP_ADDED','FOLLOWUP_VIEWED'));
ALTER TABLE audit.customer_event ADD CHECK((action IN ('CREATED','PHONE_VIEWED','FOLLOWUP_ADDED','FOLLOWUP_VIEWED') AND customer_id IS NOT NULL) OR (action IN ('DUPLICATE_DETECTED','CANCELLED') AND request_id IS NOT NULL));
ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE','ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE','ACCOUNT_CREATE','ACCOUNT_STATUS','ACCOUNT_PASSWORD_RESET','ACCOUNT_SESSIONS_REVOKE','ORGANIZATION_SCHOOL_CREATE','ORGANIZATION_SCHOOL_UPDATE','EMPLOYEE_CREATE','EMPLOYEE_UPDATE','TEACHER_CREATE','TEACHER_UPDATE','ROOM_CREATE','ROOM_UPDATE','TEACHER_QUALIFICATION_UPDATE','EMPLOYEE_ACCOUNT_BIND','PERSONNEL_UPDATE','PERSONNEL_ATTACHMENT_CREATE','PERSONNEL_ATTACHMENT_READ','PERSONNEL_ATTACHMENT_REMOVE','EMPLOYEE_EMAIL_UPDATE','PERSONNEL_EXTERNAL_UPDATE','STUDENT_CREATE','STUDENT_UPDATE','STUDENT_SENSITIVE_READ','CURRICULUM_CREATE','CURRICULUM_UPDATE','PRODUCT_CREATE','PRODUCT_UPDATE','PRICE_VERSION_CREATE','PRICE_VERSION_UPDATE','PRICE_VERSION_PUBLISH','PRICE_VERSION_RETIRE','ENROLLMENT_CREATE','ENROLLMENT_APPROVE','ENROLLMENT_DISCOUNT','RECEIPT_CONFIRM','RECEIPT_VOID','ALLOCATION_CREATE','ALLOCATION_RELEASE','CLASS_CREATE','CLASS_MEMBER_ADD','CLASS_CLOSE','LESSON_CREATE','LESSON_PUBLISH','ATTENDANCE_CONFIRM','LESSON_CLOSE','REFUND_HOURS','REFUND_PREPARE','REFUND_PAID','REFUND_CANCEL','APPROVAL_POLICY','APPROVAL_REQUEST','APPROVAL_DECIDE','PERIOD_LOCK','PERIOD_UNLOCK','SCHEDULE_PREVIEW','SCHEDULE_CONFIRM','RESOURCE_BLOCK_CREATE','RESOURCE_BLOCK_DELETE','SCHEDULE_POLICY_UPDATE','MIGRATION_STAGE','MIGRATION_DRY_RUN','MIGRATION_COMMIT','STUDENT_EXPORT','CHANNEL_CREATE','CHANNEL_UPDATE','CUSTOMER_CREATE','CUSTOMER_CANCEL','CUSTOMER_PHONE_VIEW','CUSTOMER_FOLLOWUP_CREATE','CUSTOMER_FOLLOWUP_READ'));
