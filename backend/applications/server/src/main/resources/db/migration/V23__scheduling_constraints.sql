-- 排课约束：资源不可用时间（教师活动/休假、教室预订）与学校级排课策略。
-- 旧系统把这些占用统一成"课次 + systemType"；新系统用一张资源阻塞表表达，语义更窄也更好查询。
CREATE TABLE teaching.resource_block (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  school_id uuid NOT NULL,
  -- 二选一：教师不可用或教室被预订。
  teacher_id uuid,
  room_id uuid,
  block_date date NOT NULL,
  starts_at time NOT NULL,
  ends_at time NOT NULL,
  reason varchar(100),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  CHECK(ends_at > starts_at),
  CHECK((teacher_id IS NULL) <> (room_id IS NULL)),
  FOREIGN KEY(teacher_id) REFERENCES organization.teacher(id),
  FOREIGN KEY(room_id) REFERENCES organization.room(id)
);
CREATE INDEX resource_block_school_date ON teaching.resource_block(tenant_id,school_id,block_date);
CREATE INDEX resource_block_teacher ON teaching.resource_block(tenant_id,teacher_id,block_date);
CREATE INDEX resource_block_room ON teaching.resource_block(tenant_id,room_id,block_date);

-- 学校级排课策略：最小课间间隔与"哪些冲突只警告"。
-- 默认全部按阻断处理；需要放宽时把对应开关关掉，与旧系统的 ignore 配置对应。
CREATE TABLE teaching.schedule_policy (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  school_id uuid NOT NULL,
  -- 同一资源（教师/教室）相邻两节课之间至少要间隔的分钟数。
  min_gap_minutes integer NOT NULL DEFAULT 0 CHECK(min_gap_minutes BETWEEN 0 AND 240),
  -- 教师活动/休假、教室预订、班级休息日是否阻断；false 表示只在预览里警告。
  block_teacher_unavailable boolean NOT NULL DEFAULT true,
  block_room_unavailable boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,school_id)
);

ALTER TABLE teaching.resource_block ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching.resource_block FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON teaching.resource_block
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE teaching.schedule_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching.schedule_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON teaching.schedule_policy
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

GRANT SELECT,INSERT,UPDATE,DELETE ON teaching.resource_block TO ts_app;
GRANT SELECT,INSERT,UPDATE ON teaching.schedule_policy TO ts_app;
