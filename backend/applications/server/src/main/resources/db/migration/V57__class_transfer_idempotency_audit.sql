ALTER TABLE teaching.creation_request DROP CONSTRAINT creation_request_kind_check;
ALTER TABLE teaching.creation_request
  ADD CONSTRAINT creation_request_kind_check CHECK(kind IN ('CLASS','LESSON','CLASS_TRANSFER'));

ALTER TABLE audit.teaching_event DROP CONSTRAINT teaching_event_action_check;
ALTER TABLE audit.teaching_event
  ADD CONSTRAINT teaching_event_action_check CHECK(action IN (
    'CLASS_CREATED','CLASS_MEMBER_ADDED','CLASS_CLOSED','CLASS_CANCELLED','LESSON_CREATED',
    'LESSON_PUBLISHED','ATTENDANCE_CONFIRMED','LESSON_CLOSED','LESSONS_EXPORTED',
    'OPENING_CONSUMPTION_POSTED','ENROLLMENT_CLASS_TRANSFERRED'));
