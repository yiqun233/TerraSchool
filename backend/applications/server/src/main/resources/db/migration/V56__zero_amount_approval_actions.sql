-- Non-financial enrollment decisions use a zero-value approval request; monetary actions stay positive.
ALTER TABLE approval.request DROP CONSTRAINT request_amount_check;
ALTER TABLE approval.request
  ADD CONSTRAINT request_amount_action_check CHECK (
    amount > 0 OR action IN ('ENROLLMENT_TRANSFER','ENROLLMENT_WITHDRAWAL','ENROLLMENT_DEFER')
  );
