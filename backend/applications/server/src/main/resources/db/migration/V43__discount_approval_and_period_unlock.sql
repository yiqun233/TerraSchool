-- G2: 优惠调整版本化、优惠审批动作与期间解锁独立授权。
ALTER TABLE enrollment.discount ADD COLUMN version_no bigint;
ALTER TABLE enrollment.discount ADD COLUMN previous_total numeric(14,2);
ALTER TABLE enrollment.discount ADD COLUMN new_total numeric(14,2);
ALTER TABLE enrollment.discount ADD COLUMN approval_id uuid;

WITH ranked AS (
  SELECT d.id, row_number() over (partition by d.enrollment_id order by d.created_at,d.id) AS version_no,
    coalesce((SELECT sum(c.amount) FROM enrollment.charge c WHERE c.enrollment_id=d.enrollment_id AND c.kind='ORIGINAL'),0)
      - coalesce(sum(d.amount) over (partition by d.enrollment_id order by d.created_at,d.id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING),0) AS previous_total,
    coalesce((SELECT sum(c.amount) FROM enrollment.charge c WHERE c.enrollment_id=d.enrollment_id AND c.kind='ORIGINAL'),0)
      - sum(d.amount) over (partition by d.enrollment_id order by d.created_at,d.id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS new_total
  FROM enrollment.discount d
)
UPDATE enrollment.discount d SET version_no=r.version_no,previous_total=r.previous_total,new_total=r.new_total FROM ranked r WHERE r.id=d.id;
ALTER TABLE enrollment.discount ALTER COLUMN version_no SET NOT NULL;
ALTER TABLE enrollment.discount ALTER COLUMN previous_total SET NOT NULL;
ALTER TABLE enrollment.discount ALTER COLUMN new_total SET NOT NULL;
ALTER TABLE enrollment.discount ADD CONSTRAINT discount_version_unique UNIQUE(tenant_id,enrollment_id,version_no);
ALTER TABLE enrollment.discount ADD CONSTRAINT discount_approval_fk FOREIGN KEY(tenant_id,approval_id) REFERENCES approval.request(tenant_id,id);

ALTER TABLE approval.policy DROP CONSTRAINT policy_action_check;
ALTER TABLE approval.policy ADD CHECK(action IN ('REFUND_HOURS','CASH_REFUND','ENROLLMENT_DISCOUNT','SERVICE_FEE_REFUND','SERVICE_FEE_REVERSAL'));
ALTER TABLE approval.request DROP CONSTRAINT request_action_check;
ALTER TABLE approval.request ADD CHECK(action IN ('REFUND_HOURS','CASH_REFUND','ENROLLMENT_DISCOUNT','SERVICE_FEE_REFUND','SERVICE_FEE_REVERSAL'));

ALTER TABLE identity.role_action DROP CONSTRAINT role_action_action_check;
ALTER TABLE identity.role_action ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN','STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ','PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH','ENROLLMENT_READ','ENROLLMENT_MAINTAIN','ENROLLMENT_APPROVE','FUNDS_READ','FUNDS_RECEIPT','FUNDS_ALLOCATE','TEACHING_READ','TEACHING_MAINTAIN','ATTENDANCE_RECORD','LESSON_CLOSE','REFUND_READ','REFUND_APPLY','REFUND_PAY','APPROVAL_READ','APPROVAL_MANAGE','APPROVAL_DECIDE','PERIOD_READ','PERIOD_LOCK','MIGRATION_MANAGE','CHANNEL_READ','CHANNEL_MAINTAIN','CUSTOMER_READ','CUSTOMER_MAINTAIN','CUSTOMER_PHONE_READ','ENROLLMENT_DISCOUNT','PERIOD_UNLOCK'));
ALTER TABLE identity.account_permission DROP CONSTRAINT account_permission_action_check;
ALTER TABLE identity.account_permission ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN','STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ','PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH','ENROLLMENT_READ','ENROLLMENT_MAINTAIN','ENROLLMENT_APPROVE','FUNDS_READ','FUNDS_RECEIPT','FUNDS_ALLOCATE','TEACHING_READ','TEACHING_MAINTAIN','ATTENDANCE_RECORD','LESSON_CLOSE','REFUND_READ','REFUND_APPLY','REFUND_PAY','APPROVAL_READ','APPROVAL_MANAGE','APPROVAL_DECIDE','PERIOD_READ','PERIOD_LOCK','MIGRATION_MANAGE','CHANNEL_READ','CHANNEL_MAINTAIN','CUSTOMER_READ','CUSTOMER_MAINTAIN','CUSTOMER_PHONE_READ','ENROLLMENT_DISCOUNT','PERIOD_UNLOCK'));
