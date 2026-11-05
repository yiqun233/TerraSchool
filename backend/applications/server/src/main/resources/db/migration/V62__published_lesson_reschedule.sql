-- REV-011 first slice: versioned reschedule before attendance or consumption exists.
CREATE TABLE teaching.lesson_reschedule (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  lesson_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  request_id uuid NOT NULL,
  old_lesson_date date NOT NULL,
  old_starts_at time NOT NULL,
  old_ends_at time NOT NULL,
  new_lesson_date date NOT NULL,
  new_starts_at time NOT NULL,
  new_ends_at time NOT NULL,
  reason varchar(200) NOT NULL CHECK(length(trim(reason))>0),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,lesson_id) REFERENCES teaching.lesson(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES identity.account(tenant_id,id),
  CHECK(old_ends_at>old_starts_at),
  CHECK(new_ends_at>new_starts_at)
);
CREATE INDEX lesson_reschedule_history ON teaching.lesson_reschedule(tenant_id,lesson_id,created_at,id);
ALTER TABLE teaching.lesson_reschedule ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching.lesson_reschedule FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON teaching.lesson_reschedule
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT ON teaching.lesson_reschedule TO ts_app;

CREATE TABLE teaching.lesson_reschedule_request (
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  request_id uuid NOT NULL,
  lesson_id uuid NOT NULL,
  fingerprint varchar(64) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,actor_id,request_id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES identity.account(tenant_id,id),
  FOREIGN KEY(tenant_id,lesson_id) REFERENCES teaching.lesson(tenant_id,id)
);
ALTER TABLE teaching.lesson_reschedule_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching.lesson_reschedule_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON teaching.lesson_reschedule_request
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT ON teaching.lesson_reschedule_request TO ts_app;

ALTER TABLE audit.teaching_event DROP CONSTRAINT teaching_event_action_check;
ALTER TABLE audit.teaching_event ADD CHECK(action IN (
  'CLASS_CREATED','CLASS_MEMBER_ADDED','CLASS_CLOSED','CLASS_CANCELLED','LESSON_CREATED',
  'LESSON_PUBLISHED','ATTENDANCE_CONFIRMED','LESSON_CLOSED','LESSONS_EXPORTED',
  'OPENING_CONSUMPTION_POSTED','ENROLLMENT_CLASS_TRANSFERRED','ENROLLMENT_CLASS_WITHDRAWN',
  'LESSON_RESCHEDULED'));

ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN (
  'ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE','ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE',
  'ACCOUNT_CREATE','ACCOUNT_STATUS','ACCOUNT_PASSWORD_RESET','ACCOUNT_SESSIONS_REVOKE','ORGANIZATION_SCHOOL_CREATE',
  'ORGANIZATION_SCHOOL_UPDATE','EMPLOYEE_CREATE','EMPLOYEE_UPDATE','TEACHER_CREATE','TEACHER_UPDATE','ROOM_CREATE',
  'ROOM_UPDATE','TEACHER_QUALIFICATION_UPDATE','EMPLOYEE_ACCOUNT_BIND','PERSONNEL_UPDATE','PERSONNEL_ATTACHMENT_CREATE',
  'PERSONNEL_ATTACHMENT_READ','PERSONNEL_ATTACHMENT_REMOVE','EMPLOYEE_EMAIL_UPDATE','PERSONNEL_EXTERNAL_UPDATE',
  'STUDENT_CREATE','STUDENT_UPDATE','STUDENT_SENSITIVE_READ','STUDENT_ASSIGN','STUDENT_MERGE','STUDENT_MERGE_REVERSE',
  'CURRICULUM_CREATE','CURRICULUM_UPDATE','PRODUCT_CREATE','PRODUCT_UPDATE','PRODUCT_GROUP_CREATE','PRODUCT_GROUP_UPDATE',
  'PRODUCT_GROUP_ASSIGN','PRICE_VERSION_CREATE','PRICE_VERSION_UPDATE','PRICE_VERSION_PUBLISH','PRICE_VERSION_RETIRE',
  'ENROLLMENT_CREATE','ENROLLMENT_APPROVE','ENROLLMENT_DISCOUNT','RECEIPT_CONFIRM','RECEIPT_VOID','ALLOCATION_CREATE',
  'ALLOCATION_RELEASE','CLASS_CREATE','CLASS_MEMBER_ADD','CLASS_CLOSE','CLASS_CANCEL','LESSON_CREATE','LESSON_PUBLISH',
  'LESSON_RESCHEDULE','ATTENDANCE_CONFIRM','LESSON_CLOSE','REFUND_HOURS','REFUND_PREPARE','REFUND_PAID','REFUND_CANCEL',
  'APPROVAL_POLICY','APPROVAL_REQUEST','APPROVAL_REQUEST_WITHDRAW','APPROVAL_REQUEST_EXPIRE','APPROVAL_DECIDE',
  'PERIOD_LOCK','PERIOD_UNLOCK','SCHEDULE_PREVIEW','SCHEDULE_CONFIRM','RESOURCE_BLOCK_CREATE','RESOURCE_BLOCK_DELETE',
  'SCHEDULE_POLICY_UPDATE','MIGRATION_STAGE','MIGRATION_DRY_RUN','MIGRATION_COMMIT','STUDENT_EXPORT','CHANNEL_CREATE',
  'CHANNEL_UPDATE','CUSTOMER_CREATE','CUSTOMER_CANCEL','CUSTOMER_PHONE_VIEW','CUSTOMER_FOLLOWUP_CREATE',
  'CUSTOMER_FOLLOWUP_READ','CUSTOMER_POOL_RELEASE','CUSTOMER_POOL_CLAIM','CUSTOMER_POOL_READ','CUSTOMER_MERGE',
  'CUSTOMER_MERGE_REVERSE','CUSTOMER_CONVERT_TO_STUDENT','CUSTOMER_ASSIGN'));
