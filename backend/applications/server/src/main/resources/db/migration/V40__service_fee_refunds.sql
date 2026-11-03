-- 首期沿用旧项目：报名服务费按可退余额退费，不隐式折入课时单价。
CREATE TABLE enrollment.service_fee_refund (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  enrollment_id uuid NOT NULL,
  amount numeric(14,2) NOT NULL CHECK (amount > 0),
  additional_consume numeric(14,2) NOT NULL DEFAULT 0 CHECK (additional_consume >= 0),
  reason varchar(200),
  refunded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (tenant_id,id),
  FOREIGN KEY (tenant_id,enrollment_id) REFERENCES enrollment.enrollment(tenant_id,id)
);
CREATE INDEX service_fee_refund_enrollment
  ON enrollment.service_fee_refund(tenant_id,enrollment_id,id);
ALTER TABLE enrollment.service_fee_refund ENABLE ROW LEVEL SECURITY;
ALTER TABLE enrollment.service_fee_refund FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON enrollment.service_fee_refund
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT ON enrollment.service_fee_refund TO ts_app;

ALTER TABLE enrollment.creation_request DROP CONSTRAINT creation_request_kind_check;
ALTER TABLE enrollment.creation_request ADD CHECK(kind IN ('ENROLLMENT','DISCOUNT','SERVICE_FEE_REFUND'));

ALTER TABLE funds.creation_request DROP CONSTRAINT creation_request_kind_check;
ALTER TABLE funds.creation_request ADD CHECK(kind IN ('RECEIPT','OPENING','REFUND','CASH_REFUND','SERVICE_FEE_REFUND'));

ALTER TABLE audit.enrollment_event DROP CONSTRAINT enrollment_event_action_check;
ALTER TABLE audit.enrollment_event ADD CHECK(action IN (
  'ENROLLMENT_CREATED','ENROLLMENT_APPROVED','HOURS_CANCELLED','DISCOUNT_ADDED',
  'ENROLLMENTS_EXPORTED','SERVICE_FEE_REFUNDED'));
