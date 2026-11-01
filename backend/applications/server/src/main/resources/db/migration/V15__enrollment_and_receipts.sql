-- 报名履约与资金：报名冻结商品快照与原始应收；收款、分配与账户流水分开记账。
CREATE SCHEMA enrollment;
CREATE SCHEMA funds;

CREATE TABLE enrollment.enrollment (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL REFERENCES identity.tenant(id),
  school_id uuid NOT NULL,
  school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),
  student_id uuid NOT NULL,
  product_id uuid NOT NULL,
  price_version_id uuid NOT NULL,
  -- 报名成立时冻结商品与规则：后续改价、下架都不改写这条报名。
  curriculum_name varchar(100) NOT NULL,
  product_name varchar(100) NOT NULL,
  price numeric(14,2) NOT NULL CHECK(price > 0),
  hours numeric(10,2) NOT NULL CHECK(hours > 0),
  service_fee numeric(14,2) NOT NULL DEFAULT 0 CHECK(service_fee >= 0),
  settlement_mode smallint NOT NULL CHECK(settlement_mode BETWEEN 0 AND 5),
  status varchar(20) NOT NULL DEFAULT 'PENDING_AUDIT' CHECK(status IN ('PENDING_AUDIT','ACTIVE')),
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  approved_at timestamptz,
  approved_by uuid,
  UNIQUE(tenant_id,id),
  CHECK(price >= service_fee),
  FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind)
);
CREATE INDEX enrollment_school ON enrollment.enrollment(tenant_id,school_id,created_at,id);
CREATE INDEX enrollment_student ON enrollment.enrollment(tenant_id,student_id,id);

-- 应收来源：报名原始约定与后续调整（退款减项在后续批次加入 ADJUSTMENT）。
CREATE TABLE enrollment.charge (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  enrollment_id uuid NOT NULL,
  kind varchar(16) NOT NULL CHECK(kind IN ('ORIGINAL','ADJUSTMENT')),
  amount numeric(14,2) NOT NULL,
  reason varchar(200),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,enrollment_id) REFERENCES enrollment.enrollment(tenant_id,id)
);
CREATE INDEX charge_enrollment ON enrollment.charge(tenant_id,enrollment_id,id);
-- 同一报名只允许一条原始应收，避免重复登记产生两份约定。
CREATE UNIQUE INDEX charge_original ON enrollment.charge(enrollment_id) WHERE kind='ORIGINAL';

CREATE TABLE enrollment.creation_request (
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  request_id uuid NOT NULL,
  kind varchar(20) NOT NULL CHECK(kind IN ('ENROLLMENT')),
  resource_id uuid NOT NULL,
  fingerprint varchar(200) NOT NULL,
  PRIMARY KEY(tenant_id,actor_id,request_id)
);

CREATE TABLE audit.enrollment_event (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  action varchar(40) NOT NULL CHECK(action IN ('ENROLLMENT_CREATED','ENROLLMENT_APPROVED')),
  resource_id uuid NOT NULL,
  school_id uuid NOT NULL,
  changed_fields varchar(400) NOT NULL DEFAULT '',
  trace_id uuid NOT NULL,
  happened_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 收款：已确认的收款才是可分配资金；撤销后不再可分配。
CREATE TABLE funds.receipt (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL REFERENCES identity.tenant(id),
  school_id uuid NOT NULL,
  school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),
  student_id uuid NOT NULL,
  amount numeric(14,2) NOT NULL CHECK(amount > 0),
  method varchar(16) NOT NULL CHECK(method IN ('CASH','BANK','OTHER')),
  occurred_on date NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'CONFIRMED' CHECK(status IN ('CONFIRMED','VOIDED')),
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind)
);
CREATE INDEX receipt_student ON funds.receipt(tenant_id,student_id,id);

-- 分配：把某一笔收款的一部分指向某条报名；释放后原分配保留历史但不再占用金额。
CREATE TABLE funds.allocation (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  receipt_id uuid NOT NULL,
  enrollment_id uuid NOT NULL,
  amount numeric(14,2) NOT NULL CHECK(amount > 0),
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','RELEASED')),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  released_at timestamptz,
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,receipt_id) REFERENCES funds.receipt(tenant_id,id)
);
CREATE INDEX allocation_receipt ON funds.allocation(tenant_id,receipt_id,id);
CREATE INDEX allocation_enrollment ON funds.allocation(tenant_id,enrollment_id,id);

-- 账户流水：带符号金额，余额为同一学员流水的受控汇总；不另存可变余额字段。
CREATE TABLE funds.account_entry (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  student_id uuid NOT NULL,
  kind varchar(20) NOT NULL CHECK(kind IN ('RECEIPT','ALLOCATION','RELEASE','RECEIPT_VOID')),
  amount numeric(14,2) NOT NULL,
  source_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,id)
);
CREATE INDEX account_entry_student ON funds.account_entry(tenant_id,student_id,id);

CREATE TABLE funds.creation_request (
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  request_id uuid NOT NULL,
  kind varchar(20) NOT NULL CHECK(kind IN ('RECEIPT')),
  resource_id uuid NOT NULL,
  fingerprint varchar(200) NOT NULL,
  PRIMARY KEY(tenant_id,actor_id,request_id)
);

CREATE TABLE audit.funds_event (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  action varchar(40) NOT NULL CHECK(action IN ('RECEIPT_CONFIRMED','RECEIPT_VOIDED','ALLOCATION_CREATED','ALLOCATION_RELEASED')),
  resource_id uuid NOT NULL,
  school_id uuid NOT NULL,
  amount numeric(14,2),
  changed_fields varchar(400) NOT NULL DEFAULT '',
  trace_id uuid NOT NULL,
  happened_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE enrollment.enrollment ENABLE ROW LEVEL SECURITY;
ALTER TABLE enrollment.enrollment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON enrollment.enrollment
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE enrollment.charge ENABLE ROW LEVEL SECURITY;
ALTER TABLE enrollment.charge FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON enrollment.charge
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE enrollment.creation_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE enrollment.creation_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON enrollment.creation_request
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE audit.enrollment_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.enrollment_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON audit.enrollment_event
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

ALTER TABLE funds.receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE funds.receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON funds.receipt
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE funds.allocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE funds.allocation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON funds.allocation
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE funds.account_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE funds.account_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON funds.account_entry
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE funds.creation_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE funds.creation_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON funds.creation_request
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE audit.funds_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.funds_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON audit.funds_event
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

GRANT USAGE ON SCHEMA enrollment TO ts_app;
GRANT SELECT,INSERT,UPDATE ON enrollment.enrollment TO ts_app;
GRANT SELECT,INSERT ON enrollment.charge TO ts_app;
GRANT SELECT,INSERT ON enrollment.creation_request TO ts_app;
GRANT INSERT ON audit.enrollment_event TO ts_app;

GRANT USAGE ON SCHEMA funds TO ts_app;
GRANT SELECT,INSERT,UPDATE ON funds.receipt TO ts_app;
GRANT SELECT,INSERT,UPDATE ON funds.allocation TO ts_app;
GRANT SELECT,INSERT ON funds.account_entry TO ts_app;
GRANT SELECT,INSERT ON funds.creation_request TO ts_app;
GRANT INSERT ON audit.funds_event TO ts_app;

-- 动作目录：报名查看/维护/审核，资金查看/收款/分配分别授权。
ALTER TABLE identity.role_action DROP CONSTRAINT role_action_action_check;
ALTER TABLE identity.role_action ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN','STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ','PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH','ENROLLMENT_READ','ENROLLMENT_MAINTAIN','ENROLLMENT_APPROVE','FUNDS_READ','FUNDS_RECEIPT','FUNDS_ALLOCATE'));
ALTER TABLE identity.account_permission DROP CONSTRAINT account_permission_action_check;
ALTER TABLE identity.account_permission ADD CHECK(action IN ('ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN','TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN','STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ','PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH','ENROLLMENT_READ','ENROLLMENT_MAINTAIN','ENROLLMENT_APPROVE','FUNDS_READ','FUNDS_RECEIPT','FUNDS_ALLOCATE'));

ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE','ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE','ACCOUNT_CREATE','ACCOUNT_STATUS','ACCOUNT_PASSWORD_RESET','ACCOUNT_SESSIONS_REVOKE','ORGANIZATION_SCHOOL_CREATE','ORGANIZATION_SCHOOL_UPDATE','EMPLOYEE_CREATE','EMPLOYEE_UPDATE','TEACHER_CREATE','TEACHER_UPDATE','ROOM_CREATE','ROOM_UPDATE','TEACHER_QUALIFICATION_UPDATE','EMPLOYEE_ACCOUNT_BIND','PERSONNEL_UPDATE','PERSONNEL_ATTACHMENT_CREATE','PERSONNEL_ATTACHMENT_READ','PERSONNEL_ATTACHMENT_REMOVE','EMPLOYEE_EMAIL_UPDATE','PERSONNEL_EXTERNAL_UPDATE','STUDENT_CREATE','STUDENT_UPDATE','STUDENT_SENSITIVE_READ','CURRICULUM_CREATE','CURRICULUM_UPDATE','PRODUCT_CREATE','PRODUCT_UPDATE','PRICE_VERSION_CREATE','PRICE_VERSION_UPDATE','PRICE_VERSION_PUBLISH','PRICE_VERSION_RETIRE','ENROLLMENT_CREATE','ENROLLMENT_APPROVE','RECEIPT_CONFIRM','RECEIPT_VOID','ALLOCATION_CREATE','ALLOCATION_RELEASE'));
