-- 课程与产品：课程、产品、可售价格版本；发布后的价格版本不可改写，改价走新版本。
CREATE SCHEMA product;

CREATE TABLE product.curriculum (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL REFERENCES identity.tenant(id),
  school_id uuid NOT NULL,
  school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),
  name varchar(100) NOT NULL CHECK(length(trim(name))>0),
  category varchar(50),
  subject varchar(50),
  active boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,school_id,name),
  FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind)
);
CREATE INDEX curriculum_school ON product.curriculum(tenant_id,school_id,id);

CREATE TABLE product.product (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL REFERENCES identity.tenant(id),
  school_id uuid NOT NULL,
  school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),
  curriculum_id uuid NOT NULL,
  name varchar(100) NOT NULL CHECK(length(trim(name))>0),
  -- 结算模式沿用旧系统 0–5，本批只做快照，不实现全部计算公式。
  settlement_mode smallint NOT NULL DEFAULT 1 CHECK(settlement_mode BETWEEN 0 AND 5),
  active boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,school_id,name),
  FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind),
  FOREIGN KEY(tenant_id,curriculum_id) REFERENCES product.curriculum(tenant_id,id)
);
CREATE INDEX product_school ON product.product(tenant_id,school_id,id);
CREATE INDEX product_curriculum ON product.product(tenant_id,curriculum_id,id);

CREATE TABLE product.price_version (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  product_id uuid NOT NULL,
  version_no integer NOT NULL CHECK(version_no > 0),
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','RETIRED')),
  -- 金额与课时按业务精度保存：元两位小数、课时两位小数；接口以十进制字符串传输。
  price numeric(14,2) NOT NULL CHECK(price > 0),
  hours numeric(10,2) NOT NULL CHECK(hours > 0),
  service_fee numeric(14,2) NOT NULL DEFAULT 0 CHECK(service_fee >= 0),
  settlement_mode smallint NOT NULL CHECK(settlement_mode BETWEEN 0 AND 5),
  study_days integer CHECK(study_days IS NULL OR study_days BETWEEN 1 AND 3650),
  effective_from date NOT NULL,
  effective_to date,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at timestamptz,
  UNIQUE(tenant_id,id),
  UNIQUE(product_id,version_no),
  CHECK(price >= service_fee),
  CHECK(effective_to IS NULL OR effective_to >= effective_from),
  FOREIGN KEY(tenant_id,product_id) REFERENCES product.product(tenant_id,id)
);
-- 同一产品最多只有一个已发布版本，保证“当前可售价格”唯一确定；旧版本保留为历史并置为 RETIRED。
CREATE UNIQUE INDEX price_version_published ON product.price_version(product_id) WHERE status='PUBLISHED';
CREATE INDEX price_version_product ON product.price_version(tenant_id,product_id,version_no);

-- 创建请求身份：同主体同请求号重试返回首次结果，换参数拒绝。
CREATE TABLE product.creation_request (
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  request_id uuid NOT NULL,
  kind varchar(20) NOT NULL CHECK(kind IN ('CURRICULUM','PRODUCT','PRICE_VERSION')),
  resource_id uuid NOT NULL,
  fingerprint varchar(200) NOT NULL,
  PRIMARY KEY(tenant_id,actor_id,request_id)
);

CREATE TABLE audit.product_event (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  action varchar(40) NOT NULL CHECK(action IN ('CURRICULUM_CREATED','CURRICULUM_UPDATED','PRODUCT_CREATED','PRODUCT_UPDATED','PRICE_VERSION_DRAFTED','PRICE_VERSION_UPDATED','PRICE_VERSION_PUBLISHED','PRICE_VERSION_RETIRED')),
  resource_id uuid NOT NULL,
  school_id uuid NOT NULL,
  changed_fields varchar(400) NOT NULL DEFAULT '',
  trace_id uuid NOT NULL,
  happened_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE product.curriculum ENABLE ROW LEVEL SECURITY;
ALTER TABLE product.curriculum FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON product.curriculum
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE product.product ENABLE ROW LEVEL SECURITY;
ALTER TABLE product.product FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON product.product
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE product.price_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE product.price_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON product.price_version
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE product.creation_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE product.creation_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON product.creation_request
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE audit.product_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.product_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON audit.product_event
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

GRANT USAGE ON SCHEMA product TO ts_app;
GRANT SELECT,INSERT,UPDATE ON product.curriculum TO ts_app;
GRANT SELECT,INSERT,UPDATE ON product.product TO ts_app;
GRANT SELECT,INSERT,UPDATE ON product.price_version TO ts_app;
GRANT SELECT,INSERT ON product.creation_request TO ts_app;
GRANT INSERT ON audit.product_event TO ts_app;

-- 动作目录：课程产品查看/维护与独立的价格发布权限。
ALTER TABLE identity.role_action DROP CONSTRAINT role_action_action_check;
ALTER TABLE identity.role_action ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN','STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ','PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH'));
ALTER TABLE identity.account_permission DROP CONSTRAINT account_permission_action_check;
ALTER TABLE identity.account_permission ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN','STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ','PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH'));

ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE','ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE','ACCOUNT_CREATE','ACCOUNT_STATUS','ACCOUNT_PASSWORD_RESET','ACCOUNT_SESSIONS_REVOKE','ORGANIZATION_SCHOOL_CREATE','ORGANIZATION_SCHOOL_UPDATE','EMPLOYEE_CREATE','EMPLOYEE_UPDATE','TEACHER_CREATE','TEACHER_UPDATE','ROOM_CREATE','ROOM_UPDATE','TEACHER_QUALIFICATION_UPDATE','EMPLOYEE_ACCOUNT_BIND','PERSONNEL_UPDATE','PERSONNEL_ATTACHMENT_CREATE','PERSONNEL_ATTACHMENT_READ','PERSONNEL_ATTACHMENT_REMOVE','EMPLOYEE_EMAIL_UPDATE','PERSONNEL_EXTERNAL_UPDATE','STUDENT_CREATE','STUDENT_UPDATE','STUDENT_SENSITIVE_READ','CURRICULUM_CREATE','CURRICULUM_UPDATE','PRODUCT_CREATE','PRODUCT_UPDATE','PRICE_VERSION_CREATE','PRICE_VERSION_UPDATE','PRICE_VERSION_PUBLISH','PRICE_VERSION_RETIRE'));
