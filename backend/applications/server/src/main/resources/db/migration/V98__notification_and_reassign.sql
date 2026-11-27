-- 通知中心（站内信）与教师批量改派：首版把“调课通知”落到库内，短信/微信留待外部渠道。
CREATE TABLE teaching.notification (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  school_id uuid NOT NULL,
  kind varchar(30) NOT NULL,
  title varchar(120) NOT NULL,
  body varchar(500) NOT NULL,
  ref_type varchar(20),
  ref_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  read_at timestamptz
);

COMMENT ON TABLE teaching.notification IS '站内信：调课/改派等排课事件的通知，首版学校级、不区分接收人';
COMMENT ON COLUMN teaching.notification.kind IS 'LESSON_RESCHEDULED / LESSON_SWAPPED / TEACHER_REASSIGNED';
COMMENT ON COLUMN teaching.notification.read_at IS '读时间；首版学校级已读标记';

CREATE INDEX notification_school_time ON teaching.notification(tenant_id, school_id, created_at DESC);

ALTER TABLE teaching.notification ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching.notification FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON teaching.notification
  USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON teaching.notification TO ts_app;

-- 约束必须包含 V77/V92/V94/V95/V97 的全部取值；漏一个，含该类型历史行的库会在 ADD CONSTRAINT 处失败。
ALTER TABLE audit.teaching_event DROP CONSTRAINT teaching_event_action_check;
ALTER TABLE audit.teaching_event
  ADD CONSTRAINT teaching_event_action_check CHECK(action IN (
    'CLASS_CREATED','CLASS_MEMBER_ADDED','CLASS_CLOSED','CLASS_CANCELLED','LESSON_CREATED',
    'LESSON_PUBLISHED','ATTENDANCE_CONFIRMED','LESSON_CLOSED','LESSONS_EXPORTED',
    'OPENING_CONSUMPTION_POSTED','ENROLLMENT_CLASS_TRANSFERRED','ENROLLMENT_CLASS_WITHDRAWN',
    'LESSON_RESCHEDULED','LESSON_SWAPPED','LESSON_MAKEUP_CREATED','SCHOOL_CLOSURE_CREATED',
    'SCHOOL_CLOSURE_CANCELLED','CLASS_CAPACITY_UPDATED',
    'LESSON_RESOURCES_ASSIGNED','LESSON_DELETED','LESSONS_BATCH_CREATED',
    'SWAP_SCHEME_EXECUTED','ATTENDANCE_SAVED','LESSON_NOTE_UPDATED',
    'LESSONS_REASSIGNED','NOTIFICATION_CREATED'));
