-- REV-009 first slice: same-school/same-owner profile merge, without moving business facts.
ALTER TABLE archive.student ADD COLUMN merged_into_id uuid;
ALTER TABLE archive.student ADD COLUMN merged_at timestamptz;
ALTER TABLE archive.student
  ADD CONSTRAINT student_merge_target_fk
    FOREIGN KEY (tenant_id, merged_into_id) REFERENCES archive.student(tenant_id, id),
  ADD CONSTRAINT student_merge_state_check
    CHECK ((merged_into_id IS NULL) = (merged_at IS NULL)),
  ADD CONSTRAINT student_merge_not_self_check
    CHECK (merged_into_id IS NULL OR merged_into_id <> id);
CREATE INDEX student_merge_children ON archive.student(tenant_id, merged_into_id)
  WHERE merged_into_id IS NOT NULL;
GRANT UPDATE(merged_into_id,merged_at,version,updated_at) ON archive.student TO ts_app;

CREATE TABLE archive.student_merge (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  source_student_id uuid NOT NULL,
  primary_student_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  reason varchar(200) NOT NULL CHECK(length(trim(reason))>0),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reversed_at timestamptz,
  reversed_by uuid,
  reverse_reason varchar(200),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,source_student_id) REFERENCES archive.student(tenant_id,id),
  FOREIGN KEY(tenant_id,primary_student_id) REFERENCES archive.student(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES identity.account(tenant_id,id),
  FOREIGN KEY(tenant_id,reversed_by) REFERENCES identity.account(tenant_id,id),
  CHECK(source_student_id<>primary_student_id),
  CHECK((reversed_at IS NULL AND reversed_by IS NULL AND reverse_reason IS NULL)
     OR (reversed_at IS NOT NULL AND reversed_by IS NOT NULL AND reverse_reason IS NOT NULL))
);
CREATE UNIQUE INDEX student_merge_active_source
  ON archive.student_merge(tenant_id,source_student_id) WHERE reversed_at IS NULL;
CREATE INDEX student_merge_primary_history
  ON archive.student_merge(tenant_id,primary_student_id,created_at,id);
ALTER TABLE archive.student_merge ENABLE ROW LEVEL SECURITY;
ALTER TABLE archive.student_merge FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON archive.student_merge
  USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
  WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT,UPDATE ON archive.student_merge TO ts_app;

CREATE TABLE archive.student_merge_request (
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  request_id uuid NOT NULL,
  source_student_id uuid NOT NULL,
  action varchar(20) NOT NULL CHECK(action IN ('MERGE','REVERSE')),
  merge_id uuid NOT NULL,
  fingerprint varchar(64) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,actor_id,request_id),
  FOREIGN KEY(tenant_id,source_student_id) REFERENCES archive.student(tenant_id,id),
  FOREIGN KEY(tenant_id,merge_id) REFERENCES archive.student_merge(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES identity.account(tenant_id,id)
);
ALTER TABLE archive.student_merge_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE archive.student_merge_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON archive.student_merge_request
  USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
  WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT ON archive.student_merge_request TO ts_app;

ALTER TABLE audit.student_event DROP CONSTRAINT student_event_action_check;
ALTER TABLE audit.student_event ADD CHECK(action IN (
  'STUDENT_CREATED','STUDENT_UPDATED','STUDENT_SENSITIVE_READ','STUDENT_EXPORTED','STUDENT_ASSIGNED',
  'STUDENT_MERGED','STUDENT_MERGE_REVERSED'));
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
 'ATTENDANCE_CONFIRM','LESSON_CLOSE','REFUND_HOURS','REFUND_PREPARE','REFUND_PAID','REFUND_CANCEL','APPROVAL_POLICY',
 'APPROVAL_REQUEST','APPROVAL_REQUEST_WITHDRAW','APPROVAL_REQUEST_EXPIRE','APPROVAL_DECIDE','PERIOD_LOCK',
 'PERIOD_UNLOCK','SCHEDULE_PREVIEW','SCHEDULE_CONFIRM','RESOURCE_BLOCK_CREATE','RESOURCE_BLOCK_DELETE',
 'SCHEDULE_POLICY_UPDATE','MIGRATION_STAGE','MIGRATION_DRY_RUN','MIGRATION_COMMIT','STUDENT_EXPORT','CHANNEL_CREATE',
 'CHANNEL_UPDATE','CUSTOMER_CREATE','CUSTOMER_CANCEL','CUSTOMER_PHONE_VIEW','CUSTOMER_FOLLOWUP_CREATE',
 'CUSTOMER_FOLLOWUP_READ','CUSTOMER_POOL_RELEASE','CUSTOMER_POOL_CLAIM','CUSTOMER_POOL_READ','CUSTOMER_MERGE',
 'CUSTOMER_MERGE_REVERSE','CUSTOMER_CONVERT_TO_STUDENT','CUSTOMER_ASSIGN'));
