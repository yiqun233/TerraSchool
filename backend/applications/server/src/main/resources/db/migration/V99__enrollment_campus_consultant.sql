-- 报名校区与课程顾问（销售业绩）。
--
-- 旧系统报名单上既有“报名校区”（H_Student_Register.Area_Code，非空），也有“课程顾问”
-- （Seller_Code/Seller_B/Seller_C）。本迁移补齐这两列，并把班型字典扩到旧系统的
-- 一对一 / 一对四 / 一对八 三种常见规格。
--
-- 迁移以 ts_migrator 执行：该角色不是超级用户、没有 BYPASSRLS，而业务表是 FORCE RLS，
-- 不设 app.tenant_id 时看不到任何行。回填必须逐租户设置会话变量。

-- 班型：保留原有三个，追加旧系统的一对一 / 一对四 / 一对八。
ALTER TABLE teaching.class DROP CONSTRAINT class_class_type_check;
ALTER TABLE teaching.class ADD CONSTRAINT class_class_type_check
  CHECK (class_type IN ('VIP', 'LARGE', 'NORMAL', 'ONE_ON_ONE', 'ONE_TO_FOUR', 'ONE_TO_EIGHT'));

-- 报名校区：与班级校区同构，用复合外键保证“校区确实属于该学校”。
ALTER TABLE enrollment.enrollment ADD COLUMN campus_id uuid;
ALTER TABLE enrollment.enrollment ADD COLUMN campus_kind varchar(20) NOT NULL DEFAULT 'CAMPUS'
  CHECK (campus_kind = 'CAMPUS');
ALTER TABLE enrollment.enrollment ADD CONSTRAINT enrollment_campus_fk
  FOREIGN KEY (tenant_id, campus_id, school_id, campus_kind)
  REFERENCES organization.unit (tenant_id, id, school_id, kind);
CREATE INDEX enrollment_campus ON enrollment.enrollment (tenant_id, campus_id);

-- 课程顾问：取在职员工，姓名走关联查询，不存冗余快照。
ALTER TABLE enrollment.enrollment ADD COLUMN consultant_id uuid;
ALTER TABLE enrollment.enrollment ADD CONSTRAINT enrollment_consultant_fk
  FOREIGN KEY (tenant_id, school_id, consultant_id)
  REFERENCES organization.employee (tenant_id, school_id, id);
CREATE INDEX enrollment_consultant ON enrollment.enrollment (tenant_id, school_id, consultant_id);

-- 回填：历史报名按学员档案的校区补齐（学员没有校区时留空，进班校验会跳过空值）。
DO $$
DECLARE
  t uuid;
BEGIN
  FOR t IN SELECT id FROM identity.tenant LOOP
    PERFORM set_config('app.tenant_id', t::text, true);
    UPDATE enrollment.enrollment e
       SET campus_id = s.campus_id
      FROM archive.student s
     WHERE s.tenant_id = e.tenant_id
       AND s.id = e.student_id
       AND e.campus_id IS NULL
       AND s.campus_id IS NOT NULL;
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
END $$;
