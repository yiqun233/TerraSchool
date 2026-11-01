-- 报名课程明细：一条报名可包含多门课程，各自课时与费用，作为结算模式 2–5 的计价基础。
-- 既有报名按价格版本所属产品回填一条明细，保证模式 1 行为不变。
CREATE TABLE enrollment.enrollment_course (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  enrollment_id uuid NOT NULL,
  curriculum_id uuid NOT NULL,
  -- 该课程的购买课时与费用；同一报名内合计应等于报名的总课时与价格。
  hours numeric(10,2) NOT NULL CHECK(hours > 0),
  amount numeric(14,2) NOT NULL CHECK(amount >= 0),
  -- 已退课时与已退费用，供模式 4/5 重算单价使用。
  cancelled_hours numeric(10,2) NOT NULL DEFAULT 0 CHECK(cancelled_hours >= 0),
  cancelled_amount numeric(14,2) NOT NULL DEFAULT 0 CHECK(cancelled_amount >= 0),
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,enrollment_id,curriculum_id),
  CHECK(cancelled_hours <= hours),
  CHECK(cancelled_amount <= amount)
);
CREATE INDEX enrollment_course_enrollment ON enrollment.enrollment_course(tenant_id,enrollment_id);

-- 既有报名回填：一条明细对应价格版本所属产品的课程。
INSERT INTO enrollment.enrollment_course(tenant_id,enrollment_id,curriculum_id,hours,amount)
SELECT e.tenant_id,e.id,p.curriculum_id,e.hours,e.price
FROM enrollment.enrollment e
JOIN product.price_version pv ON pv.id=e.price_version_id
JOIN product.product p ON p.id=pv.product_id
WHERE NOT EXISTS(SELECT 1 FROM enrollment.enrollment_course c WHERE c.enrollment_id=e.id);

-- 消费记录标注课次所属课程，用于按课程封顶与尾差修正。
ALTER TABLE teaching.consumption ADD COLUMN curriculum_id uuid;
UPDATE teaching.consumption c
SET curriculum_id=cl.curriculum_id
FROM teaching.lesson l
JOIN teaching.class cl ON cl.id=l.class_id
WHERE l.id=c.lesson_id AND c.curriculum_id IS NULL;

ALTER TABLE enrollment.enrollment_course ENABLE ROW LEVEL SECURITY;
ALTER TABLE enrollment.enrollment_course FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON enrollment.enrollment_course
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

GRANT SELECT,INSERT,UPDATE ON enrollment.enrollment_course TO ts_app;
