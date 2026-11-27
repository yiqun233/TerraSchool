-- 确认课次时记录“实际上课教师/教室”：排课值是计划值，确认后可能临时换人换教室。
-- 名称快照与排课值同构，避免教师/教室改名或停用后历史展示漂移。
alter table teaching.lesson
  add column actual_teacher_id uuid,
  add column actual_teacher_name varchar(50),
  add column actual_room_id uuid,
  add column actual_room_name varchar(50);

comment on column teaching.lesson.actual_teacher_id is '确认时实际授课教师；null 表示与排课值相同';
comment on column teaching.lesson.actual_room_id is '确认时实际使用教室；null 表示与排课值相同';
