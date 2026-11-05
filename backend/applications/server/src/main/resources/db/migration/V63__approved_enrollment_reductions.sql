-- Keep the reduction kind alongside the append-only receivable adjustment.
ALTER TABLE enrollment.discount
  ADD COLUMN action varchar(32) NOT NULL DEFAULT 'ENROLLMENT_DISCOUNT'
    CHECK(action IN ('ENROLLMENT_DISCOUNT','ENROLLMENT_REDUCTION','SERVICE_FEE_REDUCTION'));
