-- 已锁定期间不得改写退费历史；只允许在当前开放财务期间写冲正事实和补偿应收。
CREATE TABLE enrollment.service_fee_refund_reversal (
  tenant_id uuid NOT NULL,
  enrollment_id uuid NOT NULL,
  refund_id uuid NOT NULL UNIQUE,
  reason varchar(200),
  reversed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, refund_id),
  FOREIGN KEY (tenant_id, enrollment_id) REFERENCES enrollment.enrollment(tenant_id,id),
  FOREIGN KEY (tenant_id, refund_id) REFERENCES enrollment.service_fee_refund(tenant_id,id)
);
ALTER TABLE enrollment.service_fee_refund_reversal ENABLE ROW LEVEL SECURITY;
ALTER TABLE enrollment.service_fee_refund_reversal FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON enrollment.service_fee_refund_reversal
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT ON enrollment.service_fee_refund_reversal TO ts_app;

ALTER TABLE enrollment.creation_request DROP CONSTRAINT creation_request_kind_check;
ALTER TABLE enrollment.creation_request ADD CHECK(kind IN (
  'ENROLLMENT','DISCOUNT','SERVICE_FEE_REFUND','SERVICE_FEE_REVERSAL'));

ALTER TABLE funds.creation_request DROP CONSTRAINT creation_request_kind_check;
ALTER TABLE funds.creation_request ADD CHECK(kind IN (
  'RECEIPT','OPENING','REFUND','CASH_REFUND','SERVICE_FEE_REFUND','SERVICE_FEE_REVERSAL'));

ALTER TABLE audit.enrollment_event DROP CONSTRAINT enrollment_event_action_check;
ALTER TABLE audit.enrollment_event ADD CHECK(action IN (
  'ENROLLMENT_CREATED','ENROLLMENT_APPROVED','HOURS_CANCELLED','DISCOUNT_ADDED',
  'ENROLLMENTS_EXPORTED','SERVICE_FEE_REFUNDED','SERVICE_FEE_REFUND_REVERSED'));
