-- Freeze the candidate study end date at enrollment time and let an approved defer action
-- extend that date explicitly. Existing rows stay NULL because enrolledOn was not persisted.
ALTER TABLE enrollment.enrollment ADD COLUMN valid_until date;
ALTER TABLE approval.request ADD COLUMN requested_until date;

ALTER TABLE audit.enrollment_event DROP CONSTRAINT enrollment_event_action_check;
ALTER TABLE audit.enrollment_event ADD CHECK(action IN (
  'ENROLLMENT_CREATED','ENROLLMENT_APPROVED','HOURS_CANCELLED','DISCOUNT_ADDED',
  'ENROLLMENTS_EXPORTED','SERVICE_FEE_REFUNDED','SERVICE_FEE_REFUND_REVERSED',
  'ENROLLMENT_DEFERRED'));

