-- 教师与教室实体化：课次引用真实资源（教师 = organization.teacher，教室 = organization.room），
-- 名称仍保留为快照，便于历史展示与改名后对账。
ALTER TABLE teaching.lesson
  ADD COLUMN teacher_id uuid,
  ADD COLUMN room_id uuid;

-- 既有课次按同校同名回填；找不到同名的保持为空（自由文本是历史数据，不强行匹配）。
UPDATE teaching.lesson l
SET teacher_id=(
  SELECT t.id FROM teaching.class c
  JOIN organization.teacher t
    ON t.tenant_id=c.tenant_id AND t.school_id=c.school_id AND t.name=l.teacher_name
  WHERE c.id=l.class_id
  LIMIT 1
)
WHERE l.teacher_id IS NULL AND l.teacher_name IS NOT NULL;

UPDATE teaching.lesson l
SET room_id=(
  SELECT r.id FROM teaching.class c
  JOIN organization.room r
    ON r.tenant_id=c.tenant_id AND r.school_id=c.school_id AND r.name=l.room_name
  WHERE c.id=l.class_id
  LIMIT 1
)
WHERE l.room_id IS NULL AND l.room_name IS NOT NULL;

ALTER TABLE teaching.lesson
  ADD CONSTRAINT lesson_teacher_fk FOREIGN KEY(teacher_id) REFERENCES organization.teacher(id),
  ADD CONSTRAINT lesson_room_fk FOREIGN KEY(room_id) REFERENCES organization.room(id);

-- 冲突检测与排课查询按资源建索引。
CREATE INDEX lesson_teacher_time ON teaching.lesson(tenant_id,teacher_id,lesson_date)
  WHERE teacher_id IS NOT NULL;
CREATE INDEX lesson_room_time ON teaching.lesson(tenant_id,room_id,lesson_date)
  WHERE room_id IS NOT NULL;
