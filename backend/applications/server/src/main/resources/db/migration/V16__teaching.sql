-- 教务：班级、报名入班、课次、考勤与课次消费；关闭课次是消费与课时的唯一固化点。
CREATE SCHEMA teaching;

CREATE TABLE teaching.class (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL REFERENCES identity.tenant(id),
  school_id uuid NOT NULL,
  school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),
  curriculum_id uuid NOT NULL,
  name varchar(100) NOT NULL CHECK(length(trim(name))>0),
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','CLOSED')),
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,school_id,name),
  FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind),
  FOREIGN KEY(tenant_id,curriculum_id) REFERENCES product.curriculum(tenant_id,id)
);
CREATE INDEX class_school ON teaching.class(tenant_id,school_id,id);

-- 报名入班：同一报名首片只允许一个当前主班，历史关系保留。
CREATE TABLE teaching.enrollment_class (
  tenant_id uuid NOT NULL,
  enrollment_id uuid NOT NULL,
  class_id uuid NOT NULL,
  joined_on date NOT NULL,
  active boolean NOT NULL DEFAULT true,
  PRIMARY KEY(enrollment_id,class_id),
  FOREIGN KEY(tenant_id,enrollment_id) REFERENCES enrollment.enrollment(tenant_id,id),
  FOREIGN KEY(tenant_id,class_id) REFERENCES teaching.class(tenant_id,id)
);
CREATE UNIQUE INDEX enrollment_current_class ON teaching.enrollment_class(enrollment_id) WHERE active;

CREATE TABLE teaching.lesson (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  class_id uuid NOT NULL,
  lesson_date date NOT NULL,
  starts_at time NOT NULL,
  ends_at time NOT NULL,
  teacher_name varchar(50),
  room_name varchar(50),
  topic varchar(100),
  -- 本次授课课时：SC01 为 60 分钟 = 1.00 课时；分钟数与课时分开表达。
  hours numeric(10,2) NOT NULL CHECK(hours > 0),
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','CONFIRMED','CLOSED')),
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at timestamptz,
  confirmed_at timestamptz,
  closed_at timestamptz,
  UNIQUE(tenant_id,id),
  CHECK(ends_at > starts_at),
  FOREIGN KEY(tenant_id,class_id) REFERENCES teaching.class(tenant_id,id)
);
CREATE INDEX lesson_class ON teaching.lesson(tenant_id,class_id,lesson_date,id);

CREATE TABLE teaching.attendance (
  tenant_id uuid NOT NULL,
  lesson_id uuid NOT NULL,
  enrollment_id uuid NOT NULL,
  status varchar(16) NOT NULL CHECK(status IN ('PRESENT','ABSENT','LEAVE')),
  recorded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(lesson_id,enrollment_id),
  FOREIGN KEY(tenant_id,lesson_id) REFERENCES teaching.lesson(tenant_id,id),
  FOREIGN KEY(tenant_id,enrollment_id) REFERENCES enrollment.enrollment(tenant_id,id)
);

-- 课次消费：关闭课次时固化；同一报名×课次只允许一条有效入账。
CREATE TABLE teaching.consumption (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  enrollment_id uuid NOT NULL,
  lesson_id uuid NOT NULL,
  hours numeric(10,2) NOT NULL CHECK(hours > 0),
  amount numeric(14,2) NOT NULL CHECK(amount >= 0),
  settlement_mode smallint NOT NULL CHECK(settlement_mode BETWEEN 0 AND 5),
  price_snapshot numeric(14,2) NOT NULL,
  hours_snapshot numeric(10,2) NOT NULL,
  posted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  UNIQUE(enrollment_id,lesson_id),
  FOREIGN KEY(tenant_id,enrollment_id) REFERENCES enrollment.enrollment(tenant_id,id),
  FOREIGN KEY(tenant_id,lesson_id) REFERENCES teaching.lesson(tenant_id,id)
);
CREATE INDEX consumption_enrollment ON teaching.consumption(tenant_id,enrollment_id,id);

CREATE TABLE teaching.creation_request (
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  request_id uuid NOT NULL,
  kind varchar(20) NOT NULL CHECK(kind IN ('CLASS','LESSON')),
  resource_id uuid NOT NULL,
  fingerprint varchar(200) NOT NULL,
  PRIMARY KEY(tenant_id,actor_id,request_id)
);

CREATE TABLE audit.teaching_event (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  action varchar(40) NOT NULL CHECK(action IN ('CLASS_CREATED','CLASS_MEMBER_ADDED','CLASS_CLOSED','LESSON_CREATED','LESSON_PUBLISHED','ATTENDANCE_CONFIRMED','LESSON_CLOSED')),
  resource_id uuid NOT NULL,
  school_id uuid NOT NULL,
  changed_fields varchar(400) NOT NULL DEFAULT '',
  trace_id uuid NOT NULL,
  happened_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE teaching.class ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching.class FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON teaching.class
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE teaching.enrollment_class ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching.enrollment_class FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON teaching.enrollment_class
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE teaching.lesson ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching.lesson FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON teaching.lesson
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE teaching.attendance ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching.attendance FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON teaching.attendance
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE teaching.consumption ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching.consumption FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON teaching.consumption
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE teaching.creation_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching.creation_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON teaching.creation_request
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE audit.teaching_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.teaching_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON audit.teaching_event
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

GRANT USAGE ON SCHEMA teaching TO ts_app;
GRANT SELECT,INSERT,UPDATE ON teaching.class TO ts_app;
GRANT SELECT,INSERT,UPDATE ON teaching.enrollment_class TO ts_app;
GRANT SELECT,INSERT,UPDATE ON teaching.lesson TO ts_app;
GRANT SELECT,INSERT,UPDATE ON teaching.attendance TO ts_app;
GRANT SELECT,INSERT ON teaching.consumption TO ts_app;
GRANT SELECT,INSERT ON teaching.creation_request TO ts_app;
GRANT INSERT ON audit.teaching_event TO ts_app;

-- 动作目录：教务查看/维护、考勤登记与关闭课次分别授权。
ALTER TABLE identity.role_action DROP CONSTRAINT role_action_action_check;
ALTER TABLE identity.role_action ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN','STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ','PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH','ENROLLMENT_READ','ENROLLMENT_MAINTAIN','ENROLLMENT_APPROVE','FUNDS_READ','FUNDS_RECEIPT','FUNDS_ALLOCATE','TEACHING_READ','TEACHING_MAINTAIN','ATTENDANCE_RECORD','LESSON_CLOSE'));
ALTER TABLE identity.account_permission DROP CONSTRAINT account_permission_action_check;
ALTER TABLE identity.account_permission ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN','STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ','PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH','ENROLLMENT_READ','ENROLLMENT_MAINTAIN','ENROLLMENT_APPROVE','FUNDS_READ','FUNDS_RECEIPT','FUNDS_ALLOCATE','TEACHING_READ','TEACHING_MAINTAIN','ATTENDANCE_RECORD','LESSON_CLOSE'));

ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE','ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE','ACCOUNT_CREATE','ACCOUNT_STATUS','ACCOUNT_PASSWORD_RESET','ACCOUNT_SESSIONS_REVOKE','ORGANIZATION_SCHOOL_CREATE','ORGANIZATION_SCHOOL_UPDATE','EMPLOYEE_CREATE','EMPLOYEE_UPDATE','TEACHER_CREATE','TEACHER_UPDATE','ROOM_CREATE','ROOM_UPDATE','TEACHER_QUALIFICATION_UPDATE','EMPLOYEE_ACCOUNT_BIND','PERSONNEL_UPDATE','PERSONNEL_ATTACHMENT_CREATE','PERSONNEL_ATTACHMENT_READ','PERSONNEL_ATTACHMENT_REMOVE','EMPLOYEE_EMAIL_UPDATE','PERSONNEL_EXTERNAL_UPDATE','STUDENT_CREATE','STUDENT_UPDATE','STUDENT_SENSITIVE_READ','CURRICULUM_CREATE','CURRICULUM_UPDATE','PRODUCT_CREATE','PRODUCT_UPDATE','PRICE_VERSION_CREATE','PRICE_VERSION_UPDATE','PRICE_VERSION_PUBLISH','PRICE_VERSION_RETIRE','ENROLLMENT_CREATE','ENROLLMENT_APPROVE','RECEIPT_CONFIRM','RECEIPT_VOID','ALLOCATION_CREATE','ALLOCATION_RELEASE','CLASS_CREATE','CLASS_MEMBER_ADD','CLASS_CLOSE','LESSON_CREATE','LESSON_PUBLISH','ATTENDANCE_CONFIRM','LESSON_CLOSE'));
