-- 排课数据底座（M1）：班级归属校区与起止日期、班×课程多对多、课次挂课程、
-- 学校级时段模板、教师可授课程。口径见 doc/06-旧系统研究/手动排课界面复刻规格.md。

-- 班级：报名与上课统一落在单一校区（初版不做跨校区），并有开班/结班日期。
ALTER TABLE teaching.class
    ADD COLUMN campus_id uuid,
    ADD COLUMN campus_kind varchar(20) DEFAULT 'CAMPUS',
    ADD COLUMN begins_on date,
    ADD COLUMN ends_on date,
    ADD CONSTRAINT class_campus_kind CHECK (campus_kind = 'CAMPUS'),
    ADD CONSTRAINT class_campus_same_school
        FOREIGN KEY (tenant_id, campus_id, school_id, campus_kind)
            REFERENCES organization.unit (tenant_id, id, school_id, kind),
    ADD CONSTRAINT class_date_order
        CHECK (ends_on IS NULL OR begins_on IS NULL OR ends_on >= begins_on);

-- 班×课程：一个班可以同时上多门课程（暑期冲刺班＝数学+英语+物理+化学），
-- 每门课程在班内单独记录课时与价格；teaching.class.curriculum_id 保留为主课程。
CREATE TABLE teaching.class_curriculum (
    id uuid NOT NULL DEFAULT uuidv7(),
    tenant_id uuid NOT NULL,
    class_id uuid NOT NULL,
    curriculum_id uuid NOT NULL,
    hours numeric(10, 2) NOT NULL DEFAULT 0 CHECK (hours >= 0),
    price numeric(14, 2) NOT NULL DEFAULT 0 CHECK (price >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT class_curriculum_pk PRIMARY KEY (id),
    CONSTRAINT class_curriculum_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT class_curriculum_class_key UNIQUE (tenant_id, class_id, curriculum_id),
    CONSTRAINT class_curriculum_class_fk
        FOREIGN KEY (tenant_id, class_id) REFERENCES teaching.class (tenant_id, id),
    CONSTRAINT class_curriculum_curriculum_fk
        FOREIGN KEY (tenant_id, curriculum_id) REFERENCES product.curriculum (tenant_id, id)
);
CREATE INDEX class_curriculum_class ON teaching.class_curriculum (tenant_id, class_id);

-- 课次挂课程：一节具体课属于班里的哪门课程；排课与课时消费都按它归集。
ALTER TABLE teaching.lesson ADD COLUMN curriculum_id uuid;

-- 回填必须在每个租户的 RLS 上下文里做：迁移角色对业务表同样受 FORCE RLS 约束，
-- 不设 app.tenant_id 时看不到任何行，直接 UPDATE 会漏掉存量数据。
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM identity.tenant LOOP
        PERFORM set_config('app.tenant_id', t::text, true);

        -- 把现有班级的主课程补成一条班课程记录，保证班多课程接口对旧数据同样成立。
        INSERT INTO teaching.class_curriculum (tenant_id, class_id, curriculum_id)
        SELECT c.tenant_id, c.id, c.curriculum_id
        FROM teaching.class c
        WHERE c.tenant_id = t
          AND NOT EXISTS (SELECT 1
                          FROM teaching.class_curriculum cc
                          WHERE cc.tenant_id = c.tenant_id
                            AND cc.class_id = c.id
                            AND cc.curriculum_id = c.curriculum_id);

        UPDATE teaching.lesson l
        SET curriculum_id = c.curriculum_id
        FROM teaching.class c
        WHERE c.id = l.class_id
          AND l.tenant_id = t
          AND l.curriculum_id IS NULL;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;

ALTER TABLE teaching.lesson
    ALTER COLUMN curriculum_id SET NOT NULL,
    ADD CONSTRAINT lesson_curriculum_fk
        FOREIGN KEY (tenant_id, curriculum_id) REFERENCES product.curriculum (tenant_id, id);
CREATE INDEX lesson_curriculum ON teaching.lesson (tenant_id, curriculum_id);

-- 学校级时段模板：旧系统 H_School_Minute_Space 的口径，学校唯一，班级不单独持久化。
-- 分钟数从 0 点起算，[begin_minute, end_minute) 为一段可排课时段。
CREATE TABLE teaching.school_minute_space (
    id uuid NOT NULL DEFAULT uuidv7(),
    tenant_id uuid NOT NULL,
    school_id uuid NOT NULL,
    school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL',
    begin_minute smallint NOT NULL CHECK (begin_minute BETWEEN 0 AND 1439),
    end_minute smallint NOT NULL CHECK (end_minute BETWEEN 1 AND 1440),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT school_minute_space_pk PRIMARY KEY (id),
    CONSTRAINT school_minute_space_school_kind CHECK (school_kind = 'SCHOOL'),
    CONSTRAINT school_minute_space_order CHECK (end_minute > begin_minute),
    CONSTRAINT school_minute_space_key UNIQUE (tenant_id, school_id, begin_minute, end_minute),
    CONSTRAINT school_minute_space_school_fk
        FOREIGN KEY (tenant_id, school_id, school_kind)
            REFERENCES organization.unit (tenant_id, id, kind)
);

-- 教师可授课程：排课选教师/课程时的硬约束来源（旧系统 H_Teacher_Curricula）。
CREATE TABLE organization.teacher_curriculum (
    tenant_id uuid NOT NULL,
    teacher_id uuid NOT NULL,
    curriculum_id uuid NOT NULL,
    CONSTRAINT teacher_curriculum_pk PRIMARY KEY (teacher_id, curriculum_id),
    CONSTRAINT teacher_curriculum_teacher_fk
        FOREIGN KEY (teacher_id) REFERENCES organization.teacher (id),
    CONSTRAINT teacher_curriculum_curriculum_fk
        FOREIGN KEY (tenant_id, curriculum_id) REFERENCES product.curriculum (tenant_id, id)
);
CREATE INDEX teacher_curriculum_curriculum ON organization.teacher_curriculum (tenant_id, curriculum_id);

-- 校区路程分钟数（旧系统 H_Area_Distance）：首版只建空表，等冲突检测接入通勤判断时再维护。
CREATE TABLE organization.campus_distance (
    tenant_id uuid NOT NULL,
    from_campus_id uuid NOT NULL,
    to_campus_id uuid NOT NULL,
    minutes integer NOT NULL CHECK (minutes >= 0),
    CONSTRAINT campus_distance_pk PRIMARY KEY (from_campus_id, to_campus_id),
    CONSTRAINT campus_distance_distinct CHECK (from_campus_id <> to_campus_id),
    CONSTRAINT campus_distance_from_fk
        FOREIGN KEY (from_campus_id) REFERENCES organization.unit (id),
    CONSTRAINT campus_distance_to_fk
        FOREIGN KEY (to_campus_id) REFERENCES organization.unit (id)
);

ALTER TABLE teaching.class_curriculum ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching.class_curriculum FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON teaching.class_curriculum
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE teaching.school_minute_space ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching.school_minute_space FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON teaching.school_minute_space
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE organization.teacher_curriculum ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization.teacher_curriculum FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON organization.teacher_curriculum
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE organization.campus_distance ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization.campus_distance FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON organization.campus_distance
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON teaching.class_curriculum TO ts_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON teaching.school_minute_space TO ts_app;
GRANT SELECT, INSERT, DELETE ON organization.teacher_curriculum TO ts_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON organization.campus_distance TO ts_app;
