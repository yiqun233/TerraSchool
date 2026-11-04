-- REV-010/012: retain an approved member withdrawal without rewriting enrollment funds.
CREATE TABLE teaching.class_withdrawal (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  enrollment_id uuid NOT NULL,
  class_id uuid NOT NULL,
  approval_id uuid NOT NULL,
  reason varchar(200) NOT NULL CHECK(length(trim(reason)) > 0),
  actor_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,enrollment_id) REFERENCES enrollment.enrollment(tenant_id,id),
  FOREIGN KEY(tenant_id,class_id) REFERENCES teaching.class(tenant_id,id),
  FOREIGN KEY(tenant_id,approval_id) REFERENCES approval.request(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES identity.account(tenant_id,id)
);
CREATE INDEX class_withdrawal_enrollment
  ON teaching.class_withdrawal(tenant_id,enrollment_id,created_at,id);
ALTER TABLE teaching.class_withdrawal ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching.class_withdrawal FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON teaching.class_withdrawal
  USING (tenant_id = current_setting('app.tenant_id',true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id',true)::uuid);
GRANT SELECT,INSERT ON teaching.class_withdrawal TO ts_app;

ALTER TABLE teaching.creation_request DROP CONSTRAINT creation_request_kind_check;
ALTER TABLE teaching.creation_request
  ADD CONSTRAINT creation_request_kind_check
  CHECK(kind IN ('CLASS','LESSON','CLASS_TRANSFER','CLASS_WITHDRAWAL'));

ALTER TABLE audit.teaching_event DROP CONSTRAINT teaching_event_action_check;
ALTER TABLE audit.teaching_event
  ADD CONSTRAINT teaching_event_action_check CHECK(action IN (
    'CLASS_CREATED','CLASS_MEMBER_ADDED','CLASS_CLOSED','CLASS_CANCELLED','LESSON_CREATED',
    'LESSON_PUBLISHED','ATTENDANCE_CONFIRMED','LESSON_CLOSED','LESSONS_EXPORTED',
    'OPENING_CONSUMPTION_POSTED','ENROLLMENT_CLASS_TRANSFERRED','ENROLLMENT_CLASS_WITHDRAWN'));
