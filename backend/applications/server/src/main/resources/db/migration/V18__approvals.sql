-- 审批流第一切片：按学校+动作的审批策略，审批单的申请、通过与驳回，以及被退费动作消耗。
CREATE SCHEMA approval;

CREATE TABLE approval.policy (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL REFERENCES identity.tenant(id),
  school_id uuid NOT NULL,
  school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),
  -- 动作与业务动作同名，便于资金侧直接按动作查询策略。
  action varchar(40) NOT NULL CHECK(action IN ('REFUND_HOURS','CASH_REFUND')),
  -- 金额小于该阈值时免审批；阈值 0 表示所有金额都需要审批。
  free_below numeric(14,2) NOT NULL DEFAULT 0 CHECK(free_below >= 0),
  active boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,school_id,action),
  FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind)
);

CREATE TABLE approval.request (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  school_id uuid NOT NULL,
  action varchar(40) NOT NULL CHECK(action IN ('REFUND_HOURS','CASH_REFUND')),
  enrollment_id uuid NOT NULL,
  amount numeric(14,2) NOT NULL CHECK(amount > 0),
  reason varchar(200),
  status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED')),
  requested_by uuid NOT NULL,
  decided_by uuid,
  decision_note varchar(200),
  decided_at timestamptz,
  -- 审批单只能被业务动作消耗一次，防止一张单据反复使用。
  used_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  CHECK(decided_by IS NULL OR decided_by <> requested_by)
);
CREATE INDEX approval_request_school ON approval.request(tenant_id,school_id,status,created_at);
CREATE INDEX approval_request_enrollment ON approval.request(tenant_id,enrollment_id,created_at);
-- 同一报名同一动作只允许一张待审批单据，避免重复申请堆积。
CREATE UNIQUE INDEX approval_request_pending ON approval.request(enrollment_id,action) WHERE status='PENDING';

CREATE TABLE audit.approval_event (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  action varchar(40) NOT NULL CHECK(action IN ('POLICY_UPDATED','REQUESTED','APPROVED','REJECTED','CONSUMED')),
  resource_id uuid NOT NULL,
  school_id uuid NOT NULL,
  amount numeric(14,2),
  changed_fields varchar(400) NOT NULL DEFAULT '',
  trace_id uuid NOT NULL,
  happened_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE approval.policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE approval.policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON approval.policy
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE approval.request ENABLE ROW LEVEL SECURITY;
ALTER TABLE approval.request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON approval.request
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE audit.approval_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.approval_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON audit.approval_event
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

GRANT USAGE ON SCHEMA approval TO ts_app;
GRANT SELECT,INSERT,UPDATE ON approval.policy TO ts_app;
GRANT SELECT,INSERT,UPDATE ON approval.request TO ts_app;
GRANT INSERT ON audit.approval_event TO ts_app;

-- 动作目录：审批查看、策略维护与审批决定分别授权。
ALTER TABLE identity.role_action DROP CONSTRAINT role_action_action_check;
ALTER TABLE identity.role_action ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN','STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ','PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH','ENROLLMENT_READ','ENROLLMENT_MAINTAIN','ENROLLMENT_APPROVE','FUNDS_READ','FUNDS_RECEIPT','FUNDS_ALLOCATE','TEACHING_READ','TEACHING_MAINTAIN','ATTENDANCE_RECORD','LESSON_CLOSE','REFUND_READ','REFUND_APPLY','REFUND_PAY','APPROVAL_READ','APPROVAL_MANAGE','APPROVAL_DECIDE'));
ALTER TABLE identity.account_permission DROP CONSTRAINT account_permission_action_check;
ALTER TABLE identity.account_permission ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN','STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ','PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH','ENROLLMENT_READ','ENROLLMENT_MAINTAIN','ENROLLMENT_APPROVE','FUNDS_READ','FUNDS_RECEIPT','FUNDS_ALLOCATE','TEACHING_READ','TEACHING_MAINTAIN','ATTENDANCE_RECORD','LESSON_CLOSE','REFUND_READ','REFUND_APPLY','REFUND_PAY','APPROVAL_READ','APPROVAL_MANAGE','APPROVAL_DECIDE'));

ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE','ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE','ACCOUNT_CREATE','ACCOUNT_STATUS','ACCOUNT_PASSWORD_RESET','ACCOUNT_SESSIONS_REVOKE','ORGANIZATION_SCHOOL_CREATE','ORGANIZATION_SCHOOL_UPDATE','EMPLOYEE_CREATE','EMPLOYEE_UPDATE','TEACHER_CREATE','TEACHER_UPDATE','ROOM_CREATE','ROOM_UPDATE','TEACHER_QUALIFICATION_UPDATE','EMPLOYEE_ACCOUNT_BIND','PERSONNEL_UPDATE','PERSONNEL_ATTACHMENT_CREATE','PERSONNEL_ATTACHMENT_READ','PERSONNEL_ATTACHMENT_REMOVE','EMPLOYEE_EMAIL_UPDATE','PERSONNEL_EXTERNAL_UPDATE','STUDENT_CREATE','STUDENT_UPDATE','STUDENT_SENSITIVE_READ','CURRICULUM_CREATE','CURRICULUM_UPDATE','PRODUCT_CREATE','PRODUCT_UPDATE','PRICE_VERSION_CREATE','PRICE_VERSION_UPDATE','PRICE_VERSION_PUBLISH','PRICE_VERSION_RETIRE','ENROLLMENT_CREATE','ENROLLMENT_APPROVE','RECEIPT_CONFIRM','RECEIPT_VOID','ALLOCATION_CREATE','ALLOCATION_RELEASE','CLASS_CREATE','CLASS_MEMBER_ADD','CLASS_CLOSE','LESSON_CREATE','LESSON_PUBLISH','ATTENDANCE_CONFIRM','LESSON_CLOSE','REFUND_HOURS','REFUND_PREPARE','REFUND_PAID','REFUND_CANCEL','APPROVAL_POLICY','APPROVAL_REQUEST','APPROVAL_DECIDE'));
