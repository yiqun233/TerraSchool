-- 学员与家长档案：学员主档、联系人与关系分开建模；手机号/姓名不自动合并主档。
CREATE SCHEMA archive;

CREATE TABLE archive.contact (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL REFERENCES identity.tenant(id),
  name varchar(50) NOT NULL CHECK(length(trim(name))>0),
  phone varchar(30),
  phone_alt varchar(30),
  email varchar(100),
  wechat varchar(50),
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  -- 联系人至少有一种可核对联系方式；不要求手机号唯一，同一家长可能对应多个学员。
  CHECK(phone IS NOT NULL OR email IS NOT NULL OR wechat IS NOT NULL)
);
CREATE INDEX contact_phone ON archive.contact(tenant_id,phone);

CREATE TABLE archive.student (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL REFERENCES identity.tenant(id),
  student_no varchar(20) NOT NULL,
  name varchar(50) NOT NULL CHECK(length(trim(name))>0),
  name_english varchar(50),
  sex varchar(10) NOT NULL CHECK(sex IN ('MALE','FEMALE','UNKNOWN')),
  birthday date,
  cert_type varchar(20) CHECK(cert_type IN ('ID_CARD','PASSPORT','OTHER')),
  cert_code varchar(40),
  phone varchar(30),
  email varchar(100),
  -- 档案归属学校；跨校共享需后续显式动作，不因报名自动改写归属。
  school_id uuid NOT NULL,
  school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),
  -- 所属校区可空；有值时必须是同一学校的校区。
  campus_id uuid,
  campus_kind varchar(20) CHECK(campus_kind='CAMPUS'),
  status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,student_no),
  FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind),
  FOREIGN KEY(tenant_id,campus_id,campus_kind) REFERENCES organization.unit(tenant_id,id,kind),
  CHECK(cert_code IS NULL OR cert_type IS NOT NULL),
  CHECK(campus_id IS NULL OR campus_id<>school_id)
);
-- 证件号是硬重复：同租户同证件类型下唯一，不自动合并，由创建请求阻止。
CREATE UNIQUE INDEX student_cert ON archive.student(tenant_id,cert_type,cert_code) WHERE cert_code IS NOT NULL;
CREATE INDEX student_school_page ON archive.student(tenant_id,school_id,id);
CREATE INDEX student_name ON archive.student(tenant_id,name);

CREATE TABLE archive.student_contact (
  tenant_id uuid NOT NULL,
  student_id uuid NOT NULL,
  contact_id uuid NOT NULL,
  relation varchar(20) NOT NULL CHECK(relation IN ('FATHER','MOTHER','GUARDIAN','OTHER')),
  is_primary boolean NOT NULL DEFAULT false,
  position integer NOT NULL DEFAULT 0,
  PRIMARY KEY(student_id,contact_id),
  FOREIGN KEY(tenant_id,student_id) REFERENCES archive.student(tenant_id,id),
  FOREIGN KEY(tenant_id,contact_id) REFERENCES archive.contact(tenant_id,id)
);
-- 每个学员最多一个主要联系人，避免页面默认值不确定。
CREATE UNIQUE INDEX student_primary_contact ON archive.student_contact(student_id) WHERE is_primary;

-- 创建请求身份：同主体同请求号重试返回首次结果，不同参数拒绝。
CREATE TABLE archive.student_request (
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  request_id uuid NOT NULL,
  student_id uuid NOT NULL,
  fingerprint varchar(200) NOT NULL,
  PRIMARY KEY(tenant_id,actor_id,request_id),
  FOREIGN KEY(tenant_id,student_id) REFERENCES archive.student(tenant_id,id)
);

-- 学员号按租户递增，编号规则（前缀/位数）仍待业务确认，当前仅作可读标识。
CREATE TABLE archive.student_number (
  tenant_id uuid PRIMARY KEY REFERENCES identity.tenant(id),
  next_value bigint NOT NULL DEFAULT 1
);

CREATE TABLE audit.student_event (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  action varchar(40) NOT NULL CHECK(action IN ('STUDENT_CREATED','STUDENT_UPDATED','STUDENT_SENSITIVE_READ')),
  resource_id uuid NOT NULL,
  school_id uuid NOT NULL,
  -- 只记录发生变化的字段名称，不保存证件号、手机号等正文。
  changed_fields varchar(400) NOT NULL DEFAULT '',
  trace_id uuid NOT NULL,
  happened_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE archive.contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE archive.contact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON archive.contact
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE archive.student ENABLE ROW LEVEL SECURITY;
ALTER TABLE archive.student FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON archive.student
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE archive.student_contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE archive.student_contact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON archive.student_contact
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE archive.student_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE archive.student_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON archive.student_request
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE archive.student_number ENABLE ROW LEVEL SECURITY;
ALTER TABLE archive.student_number FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON archive.student_number
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE audit.student_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.student_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON audit.student_event
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

GRANT USAGE ON SCHEMA archive TO ts_app;
GRANT SELECT,INSERT,UPDATE ON archive.student TO ts_app;
GRANT SELECT,INSERT,UPDATE ON archive.contact TO ts_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON archive.student_contact TO ts_app;
GRANT SELECT,INSERT ON archive.student_request TO ts_app;
GRANT SELECT,INSERT,UPDATE ON archive.student_number TO ts_app;
-- 审计只追加，不开放修改和普通读取。
GRANT INSERT ON audit.student_event TO ts_app;

-- 学员档案动作进入统一权限目录；删除旧检查后重建，保持同一份动作白名单。
ALTER TABLE identity.role_action DROP CONSTRAINT role_action_action_check;
ALTER TABLE identity.role_action ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN','STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ'));
ALTER TABLE identity.account_permission DROP CONSTRAINT account_permission_action_check;
ALTER TABLE identity.account_permission ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN','STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ'));

-- 失败/拒绝审计的动作目录同步扩展。
ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE','ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE','ACCOUNT_CREATE','ACCOUNT_STATUS','ACCOUNT_PASSWORD_RESET','ACCOUNT_SESSIONS_REVOKE','ORGANIZATION_SCHOOL_CREATE','ORGANIZATION_SCHOOL_UPDATE','EMPLOYEE_CREATE','EMPLOYEE_UPDATE','TEACHER_CREATE','TEACHER_UPDATE','ROOM_CREATE','ROOM_UPDATE','TEACHER_QUALIFICATION_UPDATE','EMPLOYEE_ACCOUNT_BIND','PERSONNEL_UPDATE','PERSONNEL_ATTACHMENT_CREATE','PERSONNEL_ATTACHMENT_READ','PERSONNEL_ATTACHMENT_REMOVE','EMPLOYEE_EMAIL_UPDATE','PERSONNEL_EXTERNAL_UPDATE','STUDENT_CREATE','STUDENT_UPDATE','STUDENT_SENSITIVE_READ'));
