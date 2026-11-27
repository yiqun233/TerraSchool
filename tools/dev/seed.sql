-- 只由本地脚本对带本工程专属标签的开发库执行，不属于产品迁移。
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE SCHEMA IF NOT EXISTS dev;
CREATE TABLE IF NOT EXISTS dev.seed_history (
  seed_id text PRIMARY KEY,
  source text NOT NULL,
  applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
DO $seed$
DECLARE
  tenant uuid := uuidv7();
  school_a uuid := uuidv7();
  school_b uuid := uuidv7();
  maintainer uuid := uuidv7();
  reader uuid := uuidv7();
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-demo-v1',0));
  IF EXISTS (SELECT 1 FROM dev.seed_history WHERE seed_id='LOCAL-DEMO-001') THEN
    RETURN;
  END IF;
  INSERT INTO identity.tenant(id,name) VALUES (tenant,'本地演示机构');
  INSERT INTO organization.unit(id,tenant_id,school_id,kind,name) VALUES
    (school_a,tenant,school_a,'SCHOOL','演示学校（总部）'),
    (school_b,tenant,school_b,'SCHOOL','演示学校（分校）');
  INSERT INTO organization.unit(tenant_id,school_id,kind,name) VALUES
    (tenant,school_a,'CAMPUS','总部校区'),
    (tenant,school_a,'DEPARTMENT','总部教务部'),
    (tenant,school_b,'CAMPUS','分校校区'),
    (tenant,school_b,'DEPARTMENT','分校教务部');
  INSERT INTO identity.account(id,tenant_id,login,password_hash) VALUES
    (maintainer,tenant,'local-admin','{bcrypt}' || crypt(@@LOGIN_PASSWORD@@,gen_salt('bf',12))),
    (reader,tenant,'local-reader','{bcrypt}' || crypt(@@LOGIN_PASSWORD@@,gen_salt('bf',12)));
  INSERT INTO identity.account_permission(account_id,tenant_id,school_id,action,effect) VALUES
    (maintainer,tenant,school_a,'ORGANIZATION_READ','ALLOW'),(maintainer,tenant,school_b,'ORGANIZATION_READ','ALLOW'),(reader,tenant,school_a,'ORGANIZATION_READ','ALLOW');
  INSERT INTO identity.account_permission(tenant_id,account_id,school_id,action,effect)
    VALUES (tenant,maintainer,school_a,'ORGANIZATION_MAINTAIN','ALLOW'),(tenant,maintainer,school_b,'ORGANIZATION_MAINTAIN','ALLOW');
  INSERT INTO dev.seed_history(seed_id,source)
    VALUES ('LOCAL-DEMO-001','tools/dev/seed.sql：合成演示资料，非旧库迁移或正式管理员');
END;
$seed$;

-- 仅为带归属标记的合成开发环境开通新功能，不属于产品Flyway或默认管理员。
CREATE TABLE IF NOT EXISTS dev.authorization_seed_history (
  seed_id text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
DO $authorization_seed$
DECLARE
  owner_id uuid; tenant_id_value uuid; template_id uuid;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-authorization-v1',0));
  IF EXISTS(SELECT 1 FROM dev.authorization_seed_history WHERE seed_id='LOCAL-AUTHORIZATION-001') THEN RETURN; END IF;
  SELECT a.id,a.tenant_id INTO STRICT owner_id,tenant_id_value
    FROM identity.account a JOIN identity.tenant t ON t.id=a.tenant_id
    WHERE a.login='local-admin' AND t.name='本地演示机构' AND a.active AND t.active;
  -- 不覆盖已有管理规则；之后手工撤销也不会因再次启动恢复。
  IF NOT EXISTS(SELECT 1 FROM identity.account_permission WHERE account_id=owner_id AND action='AUTHORIZATION_MANAGE') THEN
    INSERT INTO identity.account_permission(tenant_id,account_id,action,school_id,effect)
      SELECT tenant_id_value,owner_id,'AUTHORIZATION_MANAGE',id,'ALLOW'
      FROM organization.unit WHERE tenant_id=tenant_id_value AND kind='SCHOOL';
  END IF;
  INSERT INTO identity.permission_role(tenant_id,code,name) VALUES(tenant_id_value,'LOCAL_ORG_READER','演示组织只读')
    ON CONFLICT(tenant_id,code) DO NOTHING RETURNING id INTO template_id;
  IF template_id IS NOT NULL THEN
    INSERT INTO identity.role_action(tenant_id,role_id,action,effect) VALUES(tenant_id_value,template_id,'ORGANIZATION_READ','ALLOW');
  END IF;
  INSERT INTO identity.permission_role(tenant_id,code,name) VALUES(tenant_id_value,'LOCAL_ORG_MAINTAINER','演示组织维护')
    ON CONFLICT(tenant_id,code) DO NOTHING RETURNING id INTO template_id;
  IF template_id IS NOT NULL THEN
    INSERT INTO identity.role_action(tenant_id,role_id,action,effect)
      VALUES(tenant_id_value,template_id,'ORGANIZATION_READ','ALLOW'),(tenant_id_value,template_id,'ORGANIZATION_MAINTAIN','ALLOW');
  END IF;
  INSERT INTO dev.authorization_seed_history(seed_id) VALUES('LOCAL-AUTHORIZATION-001');
END;
$authorization_seed$;

-- 本地合成管理员的一次性资源权限，不覆盖手工调整，不属于生产默认授权。
CREATE TABLE IF NOT EXISTS dev.resource_seed_history(seed_id text PRIMARY KEY,applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP);
DO $resource_seed$
DECLARE owner_id uuid; tenant_id_value uuid; action_name text;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-resources-v1',0));
 IF EXISTS(SELECT 1 FROM dev.resource_seed_history WHERE seed_id='LOCAL-RESOURCES-001') THEN RETURN; END IF;
 SELECT a.id,a.tenant_id INTO STRICT owner_id,tenant_id_value FROM identity.account a JOIN identity.tenant t ON t.id=a.tenant_id
 WHERE a.login='local-admin' AND t.name='本地演示机构' AND a.active AND t.active;
 FOREACH action_name IN ARRAY ARRAY['EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN'] LOOP
  IF NOT EXISTS(SELECT 1 FROM identity.account_permission WHERE account_id=owner_id AND action=action_name) THEN
   INSERT INTO identity.account_permission(tenant_id,account_id,action,school_id,effect)
    SELECT tenant_id_value,owner_id,action_name,id,'ALLOW' FROM organization.unit WHERE tenant_id=tenant_id_value AND kind='SCHOOL';
  END IF;
 END LOOP;
 INSERT INTO dev.resource_seed_history(seed_id) VALUES('LOCAL-RESOURCES-001');
END;
$resource_seed$;

-- 独立人事资料授权仅初始化本机演示管理员，已有手工规则不覆盖。
DO $personnel_seed$
DECLARE owner_id uuid; tenant_id_value uuid; action_name text;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-personnel-v1',0));
 IF EXISTS(SELECT 1 FROM dev.resource_seed_history WHERE seed_id='LOCAL-PERSONNEL-001') THEN RETURN; END IF;
 SELECT a.id,a.tenant_id INTO STRICT owner_id,tenant_id_value FROM identity.account a JOIN identity.tenant t ON t.id=a.tenant_id
 WHERE a.login='local-admin' AND t.name='本地演示机构' AND a.active AND t.active;
 FOREACH action_name IN ARRAY ARRAY['PERSONNEL_READ','PERSONNEL_MAINTAIN'] LOOP
  IF NOT EXISTS(SELECT 1 FROM identity.account_permission WHERE account_id=owner_id AND action=action_name) THEN
   INSERT INTO identity.account_permission(tenant_id,account_id,action,school_id,effect)
    SELECT tenant_id_value,owner_id,action_name,id,'ALLOW' FROM organization.unit WHERE tenant_id=tenant_id_value AND kind='SCHOOL';
  END IF;
 END LOOP;
 INSERT INTO dev.resource_seed_history(seed_id) VALUES('LOCAL-PERSONNEL-001');
END;
$personnel_seed$;

-- 学员档案授权同样只初始化本机演示管理员；已存在的规则和敏感字段授权不覆盖。
DO $student_seed$
DECLARE owner_id uuid; tenant_id_value uuid; action_name text;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-students-v1',0));
 IF EXISTS(SELECT 1 FROM dev.resource_seed_history WHERE seed_id='LOCAL-STUDENT-001') THEN RETURN; END IF;
 SELECT a.id,a.tenant_id INTO STRICT owner_id,tenant_id_value FROM identity.account a JOIN identity.tenant t ON t.id=a.tenant_id
 WHERE a.login='local-admin' AND t.name='本地演示机构' AND a.active AND t.active;
 FOREACH action_name IN ARRAY ARRAY['STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ'] LOOP
  IF NOT EXISTS(SELECT 1 FROM identity.account_permission WHERE account_id=owner_id AND action=action_name) THEN
   INSERT INTO identity.account_permission(tenant_id,account_id,action,school_id,effect)
    SELECT tenant_id_value,owner_id,action_name,id,'ALLOW' FROM organization.unit WHERE tenant_id=tenant_id_value AND kind='SCHOOL';
  END IF;
 END LOOP;
 INSERT INTO dev.resource_seed_history(seed_id) VALUES('LOCAL-STUDENT-001');
END;
$student_seed$;

-- 课程产品授权同样只初始化本机演示管理员；定价发布权限单独授予，便于演示“维护≠定价”。
DO $product_seed$
DECLARE owner_id uuid; tenant_id_value uuid; action_name text;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-products-v1',0));
 IF EXISTS(SELECT 1 FROM dev.resource_seed_history WHERE seed_id='LOCAL-PRODUCT-001') THEN RETURN; END IF;
 SELECT a.id,a.tenant_id INTO STRICT owner_id,tenant_id_value FROM identity.account a JOIN identity.tenant t ON t.id=a.tenant_id
 WHERE a.login='local-admin' AND t.name='本地演示机构' AND a.active AND t.active;
 FOREACH action_name IN ARRAY ARRAY['PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH'] LOOP
  IF NOT EXISTS(SELECT 1 FROM identity.account_permission WHERE account_id=owner_id AND action=action_name) THEN
   INSERT INTO identity.account_permission(tenant_id,account_id,action,school_id,effect)
    SELECT tenant_id_value,owner_id,action_name,id,'ALLOW' FROM organization.unit WHERE tenant_id=tenant_id_value AND kind='SCHOOL';
  END IF;
 END LOOP;
 INSERT INTO dev.resource_seed_history(seed_id) VALUES('LOCAL-PRODUCT-001');
END;
$product_seed$;

-- 报名与收款授权：登记、审核、收款与分配分动作授予，便于演示职责分离。
DO $enrollment_seed$
DECLARE owner_id uuid; tenant_id_value uuid; action_name text;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-enrollment-v1',0));
 IF EXISTS(SELECT 1 FROM dev.resource_seed_history WHERE seed_id='LOCAL-ENROLLMENT-001') THEN RETURN; END IF;
 SELECT a.id,a.tenant_id INTO STRICT owner_id,tenant_id_value FROM identity.account a JOIN identity.tenant t ON t.id=a.tenant_id
 WHERE a.login='local-admin' AND t.name='本地演示机构' AND a.active AND t.active;
 FOREACH action_name IN ARRAY ARRAY['ENROLLMENT_READ','ENROLLMENT_MAINTAIN','ENROLLMENT_APPROVE','FUNDS_READ','FUNDS_RECEIPT','FUNDS_ALLOCATE'] LOOP
  IF NOT EXISTS(SELECT 1 FROM identity.account_permission WHERE account_id=owner_id AND action=action_name) THEN
   INSERT INTO identity.account_permission(tenant_id,account_id,action,school_id,effect)
    SELECT tenant_id_value,owner_id,action_name,id,'ALLOW' FROM organization.unit WHERE tenant_id=tenant_id_value AND kind='SCHOOL';
  END IF;
 END LOOP;
 INSERT INTO dev.resource_seed_history(seed_id) VALUES('LOCAL-ENROLLMENT-001');
END;
$enrollment_seed$;

-- 教务授权：班级与课次维护、考勤登记、关闭课次分别授予。
DO $teaching_seed$
DECLARE owner_id uuid; tenant_id_value uuid; action_name text;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-teaching-v1',0));
 IF EXISTS(SELECT 1 FROM dev.resource_seed_history WHERE seed_id='LOCAL-TEACHING-001') THEN RETURN; END IF;
 SELECT a.id,a.tenant_id INTO STRICT owner_id,tenant_id_value FROM identity.account a JOIN identity.tenant t ON t.id=a.tenant_id
 WHERE a.login='local-admin' AND t.name='本地演示机构' AND a.active AND t.active;
 FOREACH action_name IN ARRAY ARRAY['TEACHING_READ','TEACHING_MAINTAIN','ATTENDANCE_RECORD','LESSON_CLOSE'] LOOP
  IF NOT EXISTS(SELECT 1 FROM identity.account_permission WHERE account_id=owner_id AND action=action_name) THEN
   INSERT INTO identity.account_permission(tenant_id,account_id,action,school_id,effect)
    SELECT tenant_id_value,owner_id,action_name,id,'ALLOW' FROM organization.unit WHERE tenant_id=tenant_id_value AND kind='SCHOOL';
  END IF;
 END LOOP;
 INSERT INTO dev.resource_seed_history(seed_id) VALUES('LOCAL-TEACHING-001');
END;
$teaching_seed$;

-- 退款授权：退课时与现金退款分别授予，便于演示两步流程与职责分离。
DO $refund_seed$
DECLARE owner_id uuid; tenant_id_value uuid; action_name text;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-refund-v1',0));
 IF EXISTS(SELECT 1 FROM dev.resource_seed_history WHERE seed_id='LOCAL-REFUND-001') THEN RETURN; END IF;
 SELECT a.id,a.tenant_id INTO STRICT owner_id,tenant_id_value FROM identity.account a JOIN identity.tenant t ON t.id=a.tenant_id
 WHERE a.login='local-admin' AND t.name='本地演示机构' AND a.active AND t.active;
 FOREACH action_name IN ARRAY ARRAY['REFUND_READ','REFUND_APPLY','REFUND_PAY'] LOOP
  IF NOT EXISTS(SELECT 1 FROM identity.account_permission WHERE account_id=owner_id AND action=action_name) THEN
   INSERT INTO identity.account_permission(tenant_id,account_id,action,school_id,effect)
    SELECT tenant_id_value,owner_id,action_name,id,'ALLOW' FROM organization.unit WHERE tenant_id=tenant_id_value AND kind='SCHOOL';
  END IF;
 END LOOP;
 INSERT INTO dev.resource_seed_history(seed_id) VALUES('LOCAL-REFUND-001');
END;
$refund_seed$;

-- 审批授权与演示策略：200 元以下免审批，便于演示 SC01 主例仍可单步完成。
DO $approval_seed$
DECLARE owner_id uuid; tenant_id_value uuid; action_name text; school_row record;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-approval-v1',0));
 IF EXISTS(SELECT 1 FROM dev.resource_seed_history WHERE seed_id='LOCAL-APPROVAL-001') THEN RETURN; END IF;
 SELECT a.id,a.tenant_id INTO STRICT owner_id,tenant_id_value FROM identity.account a JOIN identity.tenant t ON t.id=a.tenant_id
 WHERE a.login='local-admin' AND t.name='本地演示机构' AND a.active AND t.active;
 FOREACH action_name IN ARRAY ARRAY['APPROVAL_READ','APPROVAL_MANAGE','APPROVAL_DECIDE'] LOOP
  IF NOT EXISTS(SELECT 1 FROM identity.account_permission WHERE account_id=owner_id AND action=action_name) THEN
   INSERT INTO identity.account_permission(tenant_id,account_id,action,school_id,effect)
    SELECT tenant_id_value,owner_id,action_name,id,'ALLOW' FROM organization.unit WHERE tenant_id=tenant_id_value AND kind='SCHOOL';
  END IF;
 END LOOP;
 FOR school_row IN SELECT id FROM organization.unit WHERE tenant_id=tenant_id_value AND kind='SCHOOL' LOOP
  INSERT INTO approval.policy(tenant_id,school_id,action,free_below)
   VALUES (tenant_id_value,school_row.id,'REFUND_HOURS',200.00),
          (tenant_id_value,school_row.id,'CASH_REFUND',200.00)
   ON CONFLICT (tenant_id,school_id,action) DO NOTHING;
 END LOOP;
 INSERT INTO dev.resource_seed_history(seed_id) VALUES('LOCAL-APPROVAL-001');
END;
$approval_seed$;

-- 财务期间授权：演示账号可锁定/解锁月份，便于验证锁月拦截。
DO $period_seed$
DECLARE owner_id uuid; tenant_id_value uuid; action_name text;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-period-v1',0));
 IF EXISTS(SELECT 1 FROM dev.resource_seed_history WHERE seed_id='LOCAL-PERIOD-001') THEN RETURN; END IF;
 SELECT a.id,a.tenant_id INTO STRICT owner_id,tenant_id_value FROM identity.account a JOIN identity.tenant t ON t.id=a.tenant_id
 WHERE a.login='local-admin' AND t.name='本地演示机构' AND a.active AND t.active;
 FOREACH action_name IN ARRAY ARRAY['PERIOD_READ','PERIOD_LOCK'] LOOP
  IF NOT EXISTS(SELECT 1 FROM identity.account_permission WHERE account_id=owner_id AND action=action_name) THEN
   INSERT INTO identity.account_permission(tenant_id,account_id,action,school_id,effect)
    SELECT tenant_id_value,owner_id,action_name,id,'ALLOW' FROM organization.unit WHERE tenant_id=tenant_id_value AND kind='SCHOOL';
  END IF;
 END LOOP;
 INSERT INTO dev.resource_seed_history(seed_id) VALUES('LOCAL-PERIOD-001');
END;
$period_seed$;

-- 迁移授权：演示账号可装载暂存与确认导入。
DO $migration_seed$
DECLARE owner_id uuid; tenant_id_value uuid;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-migration-v1',0));
 IF EXISTS(SELECT 1 FROM dev.resource_seed_history WHERE seed_id='LOCAL-MIGRATION-001') THEN RETURN; END IF;
 SELECT a.id,a.tenant_id INTO STRICT owner_id,tenant_id_value FROM identity.account a JOIN identity.tenant t ON t.id=a.tenant_id
 WHERE a.login='local-admin' AND t.name='本地演示机构' AND a.active AND t.active;
 IF NOT EXISTS(SELECT 1 FROM identity.account_permission WHERE account_id=owner_id AND action='MIGRATION_MANAGE') THEN
  INSERT INTO identity.account_permission(tenant_id,account_id,action,school_id,effect)
   SELECT tenant_id_value,owner_id,'MIGRATION_MANAGE',id,'ALLOW' FROM organization.unit WHERE tenant_id=tenant_id_value AND kind='SCHOOL';
 END IF;
 INSERT INTO dev.resource_seed_history(seed_id) VALUES('LOCAL-MIGRATION-001');
END;
$migration_seed$;

-- 教务排课演示数据（M1/M2）：一校两校区、四门课程、多课程冲刺班与单课班、教师/教室、
-- 学员/报名/入班，以及本周与下周的课次（含缺教师、缺教室各一条，便于演示资源日报）。
DO $teaching_demo_seed$
DECLARE
  tenant_id_value uuid;
  school_id_value uuid;
  campus_id_value uuid;
  math_id uuid; english_id uuid; physics_id uuid; chemistry_id uuid;
  product_sprint uuid; product_math uuid; product_english uuid; product_physics uuid;
  pv_sprint uuid; pv_math uuid; pv_english uuid; pv_physics uuid;
  class_sprint uuid; class_math uuid; class_english uuid; class_physics uuid;
  teacher_zhang uuid; teacher_li uuid; teacher_wang uuid; teacher_zhao uuid;
  room_a uuid; room_b uuid;
  owner_account_id uuid;
  sprint_enrollment_a uuid; sprint_enrollment_b uuid; math_enrollment uuid; english_enrollment uuid;
  monday date;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('teraschool-local-teaching-demo-v1',0));
  IF EXISTS(SELECT 1 FROM dev.resource_seed_history WHERE seed_id='LOCAL-TEACHING-DEMO-001') THEN RETURN; END IF;

  SELECT a.tenant_id, a.id INTO STRICT tenant_id_value, owner_account_id
    FROM identity.account a JOIN identity.tenant t ON t.id=a.tenant_id
    WHERE a.login='local-admin' AND t.name='本地演示机构' AND a.active AND t.active;

  SELECT id INTO STRICT school_id_value
    FROM organization.unit
    WHERE tenant_id=tenant_id_value AND kind='SCHOOL' AND name='演示学校（总部）';
  SELECT id INTO STRICT campus_id_value
    FROM organization.unit
    WHERE tenant_id=tenant_id_value AND school_id=school_id_value AND kind='CAMPUS' AND name='总部校区';

  -- 学校级时段模板：上午、下午、晚间三段。
  INSERT INTO teaching.school_minute_space(tenant_id, school_id, begin_minute, end_minute) VALUES
    (tenant_id_value, school_id_value, 540, 720),
    (tenant_id_value, school_id_value, 840, 1050),
    (tenant_id_value, school_id_value, 1080, 1260);

  INSERT INTO product.curriculum(tenant_id, school_id, name, category, subject)
    VALUES (tenant_id_value, school_id_value, '数学', '学科', '数学') ON CONFLICT DO NOTHING;
  INSERT INTO product.curriculum(tenant_id, school_id, name, category, subject)
    VALUES (tenant_id_value, school_id_value, '英语', '学科', '英语') ON CONFLICT DO NOTHING;
  INSERT INTO product.curriculum(tenant_id, school_id, name, category, subject)
    VALUES (tenant_id_value, school_id_value, '物理', '学科', '物理') ON CONFLICT DO NOTHING;
  INSERT INTO product.curriculum(tenant_id, school_id, name, category, subject)
    VALUES (tenant_id_value, school_id_value, '化学', '学科', '化学') ON CONFLICT DO NOTHING;
  SELECT id INTO STRICT math_id FROM product.curriculum WHERE tenant_id=tenant_id_value AND school_id=school_id_value AND name='数学';
  SELECT id INTO STRICT english_id FROM product.curriculum WHERE tenant_id=tenant_id_value AND school_id=school_id_value AND name='英语';
  SELECT id INTO STRICT physics_id FROM product.curriculum WHERE tenant_id=tenant_id_value AND school_id=school_id_value AND name='物理';
  SELECT id INTO STRICT chemistry_id FROM product.curriculum WHERE tenant_id=tenant_id_value AND school_id=school_id_value AND name='化学';

  -- 产品：冲刺班以数学为主课程挂产品，实际四门课程由 class_curriculum 表达。
  INSERT INTO product.product(id, tenant_id, school_id, curriculum_id, name, settlement_mode)
    VALUES (uuidv7(), tenant_id_value, school_id_value, math_id, '暑期冲刺班', 1) RETURNING id INTO product_sprint;
  INSERT INTO product.product(id, tenant_id, school_id, curriculum_id, name, settlement_mode)
    VALUES (uuidv7(), tenant_id_value, school_id_value, math_id, '高一数学提高班', 1) RETURNING id INTO product_math;
  INSERT INTO product.product(id, tenant_id, school_id, curriculum_id, name, settlement_mode)
    VALUES (uuidv7(), tenant_id_value, school_id_value, english_id, '初二英语同步班', 1) RETURNING id INTO product_english;
  INSERT INTO product.product(id, tenant_id, school_id, curriculum_id, name, settlement_mode)
    VALUES (uuidv7(), tenant_id_value, school_id_value, physics_id, '初三物理冲刺班', 1) RETURNING id INTO product_physics;

  INSERT INTO product.price_version(id, tenant_id, product_id, version_no, status, price, hours, settlement_mode, effective_from, published_at)
    VALUES (uuidv7(), tenant_id_value, product_sprint, 1, 'PUBLISHED', 9600, 120, 1, CURRENT_DATE - 30, CURRENT_TIMESTAMP) RETURNING id INTO pv_sprint;
  INSERT INTO product.price_version(id, tenant_id, product_id, version_no, status, price, hours, settlement_mode, effective_from, published_at)
    VALUES (uuidv7(), tenant_id_value, product_math, 1, 'PUBLISHED', 4800, 60, 1, CURRENT_DATE - 30, CURRENT_TIMESTAMP) RETURNING id INTO pv_math;
  INSERT INTO product.price_version(id, tenant_id, product_id, version_no, status, price, hours, settlement_mode, effective_from, published_at)
    VALUES (uuidv7(), tenant_id_value, product_english, 1, 'PUBLISHED', 3600, 48, 1, CURRENT_DATE - 30, CURRENT_TIMESTAMP) RETURNING id INTO pv_english;
  INSERT INTO product.price_version(id, tenant_id, product_id, version_no, status, price, hours, settlement_mode, effective_from, published_at)
    VALUES (uuidv7(), tenant_id_value, product_physics, 1, 'PUBLISHED', 3600, 48, 1, CURRENT_DATE - 30, CURRENT_TIMESTAMP) RETURNING id INTO pv_physics;

  -- 教师与教室：都在总部校区。
  INSERT INTO organization.teacher(id, tenant_id, school_id, name) VALUES
    (uuidv7(), tenant_id_value, school_id_value, '张老师') RETURNING id INTO teacher_zhang;
  INSERT INTO organization.teacher(id, tenant_id, school_id, name) VALUES
    (uuidv7(), tenant_id_value, school_id_value, '李老师') RETURNING id INTO teacher_li;
  INSERT INTO organization.teacher(id, tenant_id, school_id, name) VALUES
    (uuidv7(), tenant_id_value, school_id_value, '王老师') RETURNING id INTO teacher_wang;
  INSERT INTO organization.teacher(id, tenant_id, school_id, name) VALUES
    (uuidv7(), tenant_id_value, school_id_value, '赵老师') RETURNING id INTO teacher_zhao;
  INSERT INTO organization.teacher_curriculum(tenant_id, teacher_id, curriculum_id) VALUES
    (tenant_id_value, teacher_zhang, math_id),
    (tenant_id_value, teacher_li, english_id),
    (tenant_id_value, teacher_wang, physics_id),
    (tenant_id_value, teacher_zhao, chemistry_id);

  INSERT INTO organization.room(id, tenant_id, school_id, campus_id, campus_kind, name, capacity) VALUES
    (uuidv7(), tenant_id_value, school_id_value, campus_id_value, 'CAMPUS', 'A101', 30) RETURNING id INTO room_a;
  INSERT INTO organization.room(id, tenant_id, school_id, campus_id, campus_kind, name, capacity) VALUES
    (uuidv7(), tenant_id_value, school_id_value, campus_id_value, 'CAMPUS', 'A102', 20) RETURNING id INTO room_b;

  -- 班级：冲刺班绑定四门课程，其余为单课班；都有校区与起止日期。
  monday := date_trunc('week', CURRENT_DATE)::date;
  INSERT INTO teaching.class(id, tenant_id, school_id, curriculum_id, name, class_type, group_training, capacity,
                             campus_id, campus_kind, begins_on, ends_on)
    VALUES (uuidv7(), tenant_id_value, school_id_value, math_id, '2026 暑期冲刺班', 'NORMAL', true, 20,
            campus_id_value, 'CAMPUS', monday - 14, monday + 56) RETURNING id INTO class_sprint;
  INSERT INTO teaching.class(id, tenant_id, school_id, curriculum_id, name, class_type, group_training, capacity,
                             campus_id, campus_kind, begins_on, ends_on)
    VALUES (uuidv7(), tenant_id_value, school_id_value, math_id, '高一数学单课班', 'NORMAL', false, 12,
            campus_id_value, 'CAMPUS', monday - 7, monday + 49) RETURNING id INTO class_math;
  INSERT INTO teaching.class(id, tenant_id, school_id, curriculum_id, name, class_type, group_training, capacity,
                             campus_id, campus_kind, begins_on, ends_on)
    VALUES (uuidv7(), tenant_id_value, school_id_value, english_id, '初二英语同步班', 'NORMAL', false, 16,
            campus_id_value, 'CAMPUS', monday - 7, monday + 49) RETURNING id INTO class_english;
  INSERT INTO teaching.class(id, tenant_id, school_id, curriculum_id, name, class_type, group_training, capacity,
                             campus_id, campus_kind, begins_on, ends_on)
    VALUES (uuidv7(), tenant_id_value, school_id_value, physics_id, '初三物理冲刺班', 'NORMAL', false, 16,
            campus_id_value, 'CAMPUS', monday - 7, monday + 49) RETURNING id INTO class_physics;

  INSERT INTO teaching.class_curriculum(tenant_id, class_id, curriculum_id, hours, price) VALUES
    (tenant_id_value, class_sprint, math_id, 30, 2400),
    (tenant_id_value, class_sprint, english_id, 30, 2400),
    (tenant_id_value, class_sprint, physics_id, 30, 2400),
    (tenant_id_value, class_sprint, chemistry_id, 30, 2400),
    (tenant_id_value, class_math, math_id, 60, 4800),
    (tenant_id_value, class_english, english_id, 48, 3600),
    (tenant_id_value, class_physics, physics_id, 48, 3600);

  -- 学员：总部校区在读。
  INSERT INTO archive.student(tenant_id, student_no, name, sex, school_id, campus_id, campus_kind, status, owner_id) VALUES
    (tenant_id_value, 'DEMO-0001', '陈一诺', 'FEMALE', school_id_value, campus_id_value, 'CAMPUS', 'ACTIVE', owner_account_id),
    (tenant_id_value, 'DEMO-0002', '林浩然', 'MALE', school_id_value, campus_id_value, 'CAMPUS', 'ACTIVE', owner_account_id),
    (tenant_id_value, 'DEMO-0003', '周子涵', 'FEMALE', school_id_value, campus_id_value, 'CAMPUS', 'ACTIVE', owner_account_id),
    (tenant_id_value, 'DEMO-0004', '吴思远', 'MALE', school_id_value, campus_id_value, 'CAMPUS', 'ACTIVE', owner_account_id),
    (tenant_id_value, 'DEMO-0005', '许安琪', 'FEMALE', school_id_value, campus_id_value, 'CAMPUS', 'ACTIVE', owner_account_id),
    (tenant_id_value, 'DEMO-0006', '郑嘉树', 'MALE', school_id_value, campus_id_value, 'CAMPUS', 'ACTIVE', owner_account_id);

  -- 报名：冲刺班两条（四门课程各 30 课时，合计 120 课时/9600 元），单课班各一条。
  INSERT INTO enrollment.enrollment(id, tenant_id, school_id, student_id, product_id, price_version_id,
                                    curriculum_name, product_name, price, hours, settlement_mode, status,
                                    approved_at, approved_by)
    SELECT uuidv7(), tenant_id_value, school_id_value, s.id, product_sprint, pv_sprint,
           '数学', '暑期冲刺班', 9600, 120, 1, 'ACTIVE', CURRENT_TIMESTAMP, owner_account_id
    FROM archive.student s
    WHERE s.tenant_id=tenant_id_value AND s.student_no='DEMO-0001'
    RETURNING id INTO sprint_enrollment_a;
  INSERT INTO enrollment.enrollment(id, tenant_id, school_id, student_id, product_id, price_version_id,
                                    curriculum_name, product_name, price, hours, settlement_mode, status,
                                    approved_at, approved_by)
    SELECT uuidv7(), tenant_id_value, school_id_value, s.id, product_sprint, pv_sprint,
           '数学', '暑期冲刺班', 9600, 120, 1, 'ACTIVE', CURRENT_TIMESTAMP, owner_account_id
    FROM archive.student s
    WHERE s.tenant_id=tenant_id_value AND s.student_no='DEMO-0002'
    RETURNING id INTO sprint_enrollment_b;
  INSERT INTO enrollment.enrollment(id, tenant_id, school_id, student_id, product_id, price_version_id,
                                    curriculum_name, product_name, price, hours, settlement_mode, status,
                                    approved_at, approved_by)
    SELECT uuidv7(), tenant_id_value, school_id_value, s.id, product_math, pv_math,
           '数学', '高一数学提高班', 4800, 60, 1, 'ACTIVE', CURRENT_TIMESTAMP, owner_account_id
    FROM archive.student s
    WHERE s.tenant_id=tenant_id_value AND s.student_no='DEMO-0003'
    RETURNING id INTO math_enrollment;
  INSERT INTO enrollment.enrollment(id, tenant_id, school_id, student_id, product_id, price_version_id,
                                    curriculum_name, product_name, price, hours, settlement_mode, status,
                                    approved_at, approved_by)
    SELECT uuidv7(), tenant_id_value, school_id_value, s.id, product_english, pv_english,
           '英语', '初二英语同步班', 3600, 48, 1, 'ACTIVE', CURRENT_TIMESTAMP, owner_account_id
    FROM archive.student s
    WHERE s.tenant_id=tenant_id_value AND s.student_no='DEMO-0004'
    RETURNING id INTO english_enrollment;

  INSERT INTO enrollment.enrollment_course(tenant_id, enrollment_id, curriculum_id, hours, amount) VALUES
    (tenant_id_value, sprint_enrollment_a, math_id, 30, 2400),
    (tenant_id_value, sprint_enrollment_a, english_id, 30, 2400),
    (tenant_id_value, sprint_enrollment_a, physics_id, 30, 2400),
    (tenant_id_value, sprint_enrollment_a, chemistry_id, 30, 2400),
    (tenant_id_value, sprint_enrollment_b, math_id, 30, 2400),
    (tenant_id_value, sprint_enrollment_b, english_id, 30, 2400),
    (tenant_id_value, sprint_enrollment_b, physics_id, 30, 2400),
    (tenant_id_value, sprint_enrollment_b, chemistry_id, 30, 2400),
    (tenant_id_value, math_enrollment, math_id, 60, 4800),
    (tenant_id_value, english_enrollment, english_id, 48, 3600);

  INSERT INTO teaching.enrollment_class(tenant_id, enrollment_id, class_id, joined_on, active) VALUES
    (tenant_id_value, sprint_enrollment_a, class_sprint, CURRENT_DATE - 14, true),
    (tenant_id_value, sprint_enrollment_b, class_sprint, CURRENT_DATE - 14, true),
    (tenant_id_value, math_enrollment, class_math, CURRENT_DATE - 7, true),
    (tenant_id_value, english_enrollment, class_english, CURRENT_DATE - 7, true);

  -- 课次：本周与下周。历史课次已确认、当天已发布、未来为草稿；
  -- 物理班留一条缺教师、英语班留一条缺教室，资源日报会显示对应待办。
  INSERT INTO teaching.lesson(tenant_id, class_id, lesson_date, starts_at, ends_at, teacher_name, room_name,
                              topic, hours, teacher_id, room_id, curriculum_id, status)
  SELECT tenant_id_value, class_sprint, monday + v.d, v.starts, v.ends, v.teacher_name, 'A101',
         v.topic, 2, v.teacher_id, room_a, v.curriculum_id, v.status
  FROM (VALUES
    (0, time '09:00', time '11:00', '张老师', '暑期冲刺·数学', teacher_zhang, math_id, 'CONFIRMED'),
    (2, time '09:00', time '11:00', '李老师', '暑期冲刺·英语', teacher_li, english_id, 'CONFIRMED'),
    (4, time '09:00', time '11:00', '王老师', '暑期冲刺·物理', teacher_wang, physics_id, 'CONFIRMED'),
    (7, time '09:00', time '11:00', '赵老师', '暑期冲刺·化学', teacher_zhao, chemistry_id, 'PUBLISHED'),
    (9, time '09:00', time '11:00', '张老师', '暑期冲刺·数学', teacher_zhang, math_id, 'DRAFT'),
    (11, time '09:00', time '11:00', '李老师', '暑期冲刺·英语', teacher_li, english_id, 'DRAFT')
  ) AS v(d, starts, ends, teacher_name, topic, teacher_id, curriculum_id, status);

  INSERT INTO teaching.lesson(tenant_id, class_id, lesson_date, starts_at, ends_at, teacher_name, room_name,
                              topic, hours, teacher_id, room_id, curriculum_id, status)
  SELECT tenant_id_value, class_math, monday + v.d, time '14:00', time '15:30', '张老师', 'A102',
         '高一数学', 1.5, teacher_zhang, room_b, math_id, v.status
  FROM (VALUES (1, 'CONFIRMED'), (3, 'PUBLISHED'), (8, 'DRAFT'), (10, 'DRAFT')) AS v(d, status);

  INSERT INTO teaching.lesson(tenant_id, class_id, lesson_date, starts_at, ends_at, teacher_name, room_name,
                              topic, hours, teacher_id, room_id, curriculum_id, status)
  VALUES
    (tenant_id_value, class_english, monday + 1, time '16:00', time '17:30', '李老师', 'A102', '初二英语', 1.5, teacher_li, room_b, english_id, 'CONFIRMED'),
    (tenant_id_value, class_english, monday + 4, time '16:00', time '17:30', '李老师', NULL, '初二英语', 1.5, teacher_li, NULL, english_id, 'PUBLISHED'),
    (tenant_id_value, class_english, monday + 8, time '16:00', time '17:30', '李老师', 'A102', '初二英语', 1.5, teacher_li, room_b, english_id, 'DRAFT');

  INSERT INTO teaching.lesson(tenant_id, class_id, lesson_date, starts_at, ends_at, teacher_name, room_name,
                              topic, hours, teacher_id, room_id, curriculum_id, status)
  VALUES
    (tenant_id_value, class_physics, monday + 2, time '10:00', time '12:00', NULL, 'A101', '初三物理', 2, NULL, room_a, physics_id, 'PUBLISHED'),
    (tenant_id_value, class_physics, monday + 5, time '10:00', time '12:00', '王老师', 'A101', '初三物理', 2, teacher_wang, room_a, physics_id, 'DRAFT');

  INSERT INTO dev.resource_seed_history(seed_id) VALUES('LOCAL-TEACHING-DEMO-001');
END;
$teaching_demo_seed$;
