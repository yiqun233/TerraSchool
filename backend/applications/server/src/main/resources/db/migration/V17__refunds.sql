-- 退款与结算：退课时（履约退量 + 应收减项）、账户冻结、现金退款单与实际支付确认。
CREATE TABLE enrollment.hours_cancellation (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  enrollment_id uuid NOT NULL,
  hours numeric(10,2) NOT NULL CHECK(hours > 0),
  amount numeric(14,2) NOT NULL CHECK(amount > 0),
  reason varchar(200),
  applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,enrollment_id) REFERENCES enrollment.enrollment(tenant_id,id)
);
CREATE INDEX hours_cancellation_enrollment ON enrollment.hours_cancellation(tenant_id,enrollment_id,id);

-- 账户冻结：现金退款准备时冻结资金，确认已付或取消时结转/释放。
CREATE TABLE funds.reservation (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  student_id uuid NOT NULL,
  amount numeric(14,2) NOT NULL CHECK(amount > 0),
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','SETTLED','RELEASED')),
  refund_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id)
);
CREATE INDEX reservation_student ON funds.reservation(tenant_id,student_id,id);

-- 现金退款单：准备（冻结）→ 实际已付 / 取消；实际支付与数据库提交不原子，结果不确定时不自动再付。
CREATE TABLE funds.cash_refund (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL REFERENCES identity.tenant(id),
  school_id uuid NOT NULL,
  school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),
  student_id uuid NOT NULL,
  enrollment_id uuid NOT NULL,
  reservation_id uuid NOT NULL,
  amount numeric(14,2) NOT NULL CHECK(amount > 0),
  status varchar(16) NOT NULL DEFAULT 'PREPARED' CHECK(status IN ('PREPARED','PAID','CANCELLED')),
  reference varchar(100),
  version bigint NOT NULL DEFAULT 0,
  prepared_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  paid_at timestamptz,
  paid_by uuid,
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind),
  FOREIGN KEY(tenant_id,reservation_id) REFERENCES funds.reservation(tenant_id,id)
);
CREATE INDEX cash_refund_enrollment ON funds.cash_refund(tenant_id,enrollment_id,id);

ALTER TABLE enrollment.hours_cancellation ENABLE ROW LEVEL SECURITY;
ALTER TABLE enrollment.hours_cancellation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON enrollment.hours_cancellation
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE funds.reservation ENABLE ROW LEVEL SECURITY;
ALTER TABLE funds.reservation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON funds.reservation
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE funds.cash_refund ENABLE ROW LEVEL SECURITY;
ALTER TABLE funds.cash_refund FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON funds.cash_refund
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

GRANT SELECT,INSERT ON enrollment.hours_cancellation TO ts_app;
GRANT SELECT,INSERT,UPDATE ON funds.reservation TO ts_app;
GRANT SELECT,INSERT,UPDATE ON funds.cash_refund TO ts_app;

-- 账户流水新增现金退出类型。
ALTER TABLE funds.account_entry DROP CONSTRAINT account_entry_kind_check;
ALTER TABLE funds.account_entry ADD CHECK(kind IN ('RECEIPT','ALLOCATION','RELEASE','RECEIPT_VOID','CASH_REFUND'));

-- 退款请求身份：同请求号重试不重复退课时或冻结。
ALTER TABLE funds.creation_request DROP CONSTRAINT creation_request_kind_check;
ALTER TABLE funds.creation_request ADD CHECK(kind IN ('RECEIPT','REFUND','CASH_REFUND'));

-- 审计动作扩展：退课时与资金调整记在报名域，冻结/支付记在资金域。
ALTER TABLE audit.enrollment_event DROP CONSTRAINT enrollment_event_action_check;
ALTER TABLE audit.enrollment_event ADD CHECK(action IN ('ENROLLMENT_CREATED','ENROLLMENT_APPROVED','HOURS_CANCELLED'));
ALTER TABLE audit.funds_event DROP CONSTRAINT funds_event_action_check;
ALTER TABLE audit.funds_event ADD CHECK(action IN ('RECEIPT_CONFIRMED','RECEIPT_VOIDED','ALLOCATION_CREATED','ALLOCATION_RELEASED','REFUND_REBALANCED','RESERVATION_CREATED','CASH_REFUND_PAID','RESERVATION_RELEASED'));

-- 动作目录：退课时与退到账户、现金退款分别授权。
ALTER TABLE identity.role_action DROP CONSTRAINT role_action_action_check;
ALTER TABLE identity.role_action ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN','STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ','PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH','ENROLLMENT_READ','ENROLLMENT_MAINTAIN','ENROLLMENT_APPROVE','FUNDS_READ','FUNDS_RECEIPT','FUNDS_ALLOCATE','TEACHING_READ','TEACHING_MAINTAIN','ATTENDANCE_RECORD','LESSON_CLOSE','REFUND_READ','REFUND_APPLY','REFUND_PAY'));
ALTER TABLE identity.account_permission DROP CONSTRAINT account_permission_action_check;
ALTER TABLE identity.account_permission ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN','STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ','PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH','ENROLLMENT_READ','ENROLLMENT_MAINTAIN','ENROLLMENT_APPROVE','FUNDS_READ','FUNDS_RECEIPT','FUNDS_ALLOCATE','TEACHING_READ','TEACHING_MAINTAIN','ATTENDANCE_RECORD','LESSON_CLOSE','REFUND_READ','REFUND_APPLY','REFUND_PAY'));

ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE','ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE','ACCOUNT_CREATE','ACCOUNT_STATUS','ACCOUNT_PASSWORD_RESET','ACCOUNT_SESSIONS_REVOKE','ORGANIZATION_SCHOOL_CREATE','ORGANIZATION_SCHOOL_UPDATE','EMPLOYEE_CREATE','EMPLOYEE_UPDATE','TEACHER_CREATE','TEACHER_UPDATE','ROOM_CREATE','ROOM_UPDATE','TEACHER_QUALIFICATION_UPDATE','EMPLOYEE_ACCOUNT_BIND','PERSONNEL_UPDATE','PERSONNEL_ATTACHMENT_CREATE','PERSONNEL_ATTACHMENT_READ','PERSONNEL_ATTACHMENT_REMOVE','EMPLOYEE_EMAIL_UPDATE','PERSONNEL_EXTERNAL_UPDATE','STUDENT_CREATE','STUDENT_UPDATE','STUDENT_SENSITIVE_READ','CURRICULUM_CREATE','CURRICULUM_UPDATE','PRODUCT_CREATE','PRODUCT_UPDATE','PRICE_VERSION_CREATE','PRICE_VERSION_UPDATE','PRICE_VERSION_PUBLISH','PRICE_VERSION_RETIRE','ENROLLMENT_CREATE','ENROLLMENT_APPROVE','RECEIPT_CONFIRM','RECEIPT_VOID','ALLOCATION_CREATE','ALLOCATION_RELEASE','CLASS_CREATE','CLASS_MEMBER_ADD','CLASS_CLOSE','LESSON_CREATE','LESSON_PUBLISH','ATTENDANCE_CONFIRM','LESSON_CLOSE','REFUND_HOURS','REFUND_PREPARE','REFUND_PAID','REFUND_CANCEL'));
