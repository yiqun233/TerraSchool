CREATE TABLE teaching.lesson_makeup (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  source_lesson_id uuid NOT NULL,
  makeup_lesson_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  request_id uuid NOT NULL,
  fingerprint varchar(64) NOT NULL,
  reason varchar(200) NOT NULL CHECK(length(trim(reason))>0),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,makeup_lesson_id),
  UNIQUE(tenant_id,actor_id,request_id),
  FOREIGN KEY(tenant_id,source_lesson_id) REFERENCES teaching.lesson(tenant_id,id),
  FOREIGN KEY(tenant_id,makeup_lesson_id) REFERENCES teaching.lesson(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES identity.account(tenant_id,id)
);
ALTER TABLE teaching.lesson_makeup ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching.lesson_makeup FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON teaching.lesson_makeup
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT ON teaching.lesson_makeup TO ts_app;

ALTER TABLE audit.teaching_event DROP CONSTRAINT teaching_event_action_check;
ALTER TABLE audit.teaching_event ADD CHECK(action IN (
  'CLASS_CREATED','CLASS_MEMBER_ADDED','CLASS_CLOSED','CLASS_CANCELLED','LESSON_CREATED',
  'LESSON_PUBLISHED','ATTENDANCE_CONFIRMED','LESSON_CLOSED','LESSONS_EXPORTED',
  'OPENING_CONSUMPTION_POSTED','ENROLLMENT_CLASS_TRANSFERRED','ENROLLMENT_CLASS_WITHDRAWN',
  'LESSON_RESCHEDULED','LESSON_SWAPPED','LESSON_MAKEUP_CREATED'));
