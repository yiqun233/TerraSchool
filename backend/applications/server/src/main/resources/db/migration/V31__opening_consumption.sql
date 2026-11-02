-- 期初课时：迁移时把旧库"已消耗课时/已消耗金额"带进新系统。
-- 这些消耗没有对应课次，因此允许 lesson_id 为空，并用 source 区分课次消费与期初消费；
-- 现有"按班封顶""可退课时""课时对账"都从 teaching.consumption 汇总，所以期初会自动被算进去。
ALTER TABLE teaching.consumption ALTER COLUMN lesson_id DROP NOT NULL;
ALTER TABLE teaching.consumption
  ADD COLUMN source varchar(16) NOT NULL DEFAULT 'LESSON' CHECK(source IN ('LESSON','OPENING'));

-- 课次消费仍然一条课次一条；期初消费每个报名只允许一条，避免重复带入。
ALTER TABLE teaching.consumption DROP CONSTRAINT consumption_enrollment_id_lesson_id_key;
CREATE UNIQUE INDEX consumption_lesson_once ON teaching.consumption(enrollment_id,lesson_id)
  WHERE source='LESSON';
CREATE UNIQUE INDEX consumption_opening_once ON teaching.consumption(enrollment_id)
  WHERE source='OPENING';

ALTER TABLE audit.teaching_event DROP CONSTRAINT teaching_event_action_check;
ALTER TABLE audit.teaching_event
  ADD CHECK(action IN ('CLASS_CREATED','CLASS_MEMBER_ADDED','CLASS_CLOSED','LESSON_CREATED','LESSON_PUBLISHED','ATTENDANCE_CONFIRMED','LESSON_CLOSED','LESSONS_EXPORTED','OPENING_CONSUMPTION_POSTED'));
