-- 班型、结算模式 0 解析留痕、服务费与优惠。
-- 旧系统模式 0 是"自动"：VIP 走平均价格(1)、大班走课程匹配(2)。班型原本挂在班级上，这里补到 class。

ALTER TABLE teaching.class
  ADD COLUMN class_type varchar(16) NOT NULL DEFAULT 'NORMAL'
    CHECK(class_type IN ('VIP','LARGE','NORMAL'));

-- 模式 0 在首次产生金额事实时解析成 1/2 并持久化，保证消费与退费用同一口径；
-- 保留解析前的声明值与解析时间，便于追溯"这条报名当初是怎么算的"。
ALTER TABLE enrollment.enrollment
  ADD COLUMN declared_settlement_mode integer,
  ADD COLUMN settlement_mode_resolved_at timestamptz;

UPDATE enrollment.enrollment
SET declared_settlement_mode=settlement_mode
WHERE declared_settlement_mode IS NULL;

-- 优惠：一次性减免，落到应收上；不做优惠券/促销规则。
CREATE TABLE enrollment.discount (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  enrollment_id uuid NOT NULL,
  amount numeric(14,2) NOT NULL CHECK(amount > 0),
  reason varchar(200),
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,enrollment_id,id),
  FOREIGN KEY(tenant_id,enrollment_id) REFERENCES enrollment.enrollment(tenant_id,id)
);
CREATE INDEX discount_enrollment ON enrollment.discount(tenant_id,enrollment_id);

ALTER TABLE enrollment.discount ENABLE ROW LEVEL SECURITY;
ALTER TABLE enrollment.discount FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON enrollment.discount
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

GRANT SELECT,INSERT ON enrollment.discount TO ts_app;

-- 审计动作：班型变更与优惠登记。
ALTER TABLE audit.enrollment_event DROP CONSTRAINT enrollment_event_action_check;
ALTER TABLE audit.enrollment_event
  ADD CHECK(action IN ('ENROLLMENT_CREATED','ENROLLMENT_APPROVED','HOURS_CANCELLED','DISCOUNT_ADDED'));

ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE','ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE','ACCOUNT_CREATE','ACCOUNT_STATUS','ACCOUNT_PASSWORD_RESET','ACCOUNT_SESSIONS_REVOKE','ORGANIZATION_SCHOOL_CREATE','ORGANIZATION_SCHOOL_UPDATE','EMPLOYEE_CREATE','EMPLOYEE_UPDATE','TEACHER_CREATE','TEACHER_UPDATE','ROOM_CREATE','ROOM_UPDATE','TEACHER_QUALIFICATION_UPDATE','EMPLOYEE_ACCOUNT_BIND','PERSONNEL_UPDATE','PERSONNEL_ATTACHMENT_CREATE','PERSONNEL_ATTACHMENT_READ','PERSONNEL_ATTACHMENT_REMOVE','EMPLOYEE_EMAIL_UPDATE','PERSONNEL_EXTERNAL_UPDATE','STUDENT_CREATE','STUDENT_UPDATE','STUDENT_SENSITIVE_READ','CURRICULUM_CREATE','CURRICULUM_UPDATE','PRODUCT_CREATE','PRODUCT_UPDATE','PRICE_VERSION_CREATE','PRICE_VERSION_UPDATE','PRICE_VERSION_PUBLISH','PRICE_VERSION_RETIRE','ENROLLMENT_CREATE','ENROLLMENT_APPROVE','ENROLLMENT_DISCOUNT','RECEIPT_CONFIRM','RECEIPT_VOID','ALLOCATION_CREATE','ALLOCATION_RELEASE','CLASS_CREATE','CLASS_MEMBER_ADD','CLASS_CLOSE','LESSON_CREATE','LESSON_PUBLISH','ATTENDANCE_CONFIRM','LESSON_CLOSE','REFUND_HOURS','REFUND_PREPARE','REFUND_PAID','REFUND_CANCEL','APPROVAL_POLICY','APPROVAL_REQUEST','APPROVAL_DECIDE','PERIOD_LOCK','PERIOD_UNLOCK','SCHEDULE_PREVIEW','SCHEDULE_CONFIRM','RESOURCE_BLOCK_CREATE','RESOURCE_BLOCK_DELETE','SCHEDULE_POLICY_UPDATE'));
