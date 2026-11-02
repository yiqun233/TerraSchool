-- 期初余额：旧库迁移时把学员的历史余额带进新系统，作为一条独立账户分录，
-- 与"收款/分配/退费"区分开，便于对账时单独核对。
ALTER TABLE funds.account_entry DROP CONSTRAINT account_entry_kind_check;
ALTER TABLE funds.account_entry
  ADD CHECK(kind IN ('RECEIPT','ALLOCATION','RELEASE','RECEIPT_VOID','CASH_REFUND','OPENING'));

-- 幂等台账允许 OPENING 类型，与收款/退款并列。
ALTER TABLE funds.creation_request DROP CONSTRAINT creation_request_kind_check;
ALTER TABLE funds.creation_request
  ADD CHECK(kind IN ('RECEIPT','REFUND','CASH_REFUND','OPENING'));

ALTER TABLE audit.funds_event DROP CONSTRAINT funds_event_action_check;
ALTER TABLE audit.funds_event
  ADD CHECK(action IN ('RECEIPT_CONFIRMED','RECEIPT_VOIDED','ALLOCATION_CREATED','ALLOCATION_RELEASED','REFUND_REBALANCED','RESERVATION_CREATED','CASH_REFUND_PAID','RESERVATION_RELEASED','RECEIPTS_EXPORTED','OPENING_POSTED'));
