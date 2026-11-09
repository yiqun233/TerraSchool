-- REV-022：多租户品牌主题（DEC-025）。
--
-- 品牌只含呈现信息：预设 seed、服务端派生令牌、显示名与 Logo 地址。
-- 租户通过 app.tenant_id 读写自己的记录；平台只读/代改呈现信息，仍不获得任何业务表权限。

CREATE TABLE identity.tenant_branding (
  tenant_id uuid PRIMARY KEY REFERENCES identity.tenant(id),
  primary_seed char(7) NOT NULL CHECK (primary_seed ~ '^#[0-9a-f]{6}$'),
  brand char(7) NOT NULL CHECK (brand ~ '^#[0-9a-f]{6}$'),
  brand_hover char(7) NOT NULL CHECK (brand_hover ~ '^#[0-9a-f]{6}$'),
  brand_active char(7) NOT NULL CHECK (brand_active ~ '^#[0-9a-f]{6}$'),
  brand_soft char(7) NOT NULL CHECK (brand_soft ~ '^#[0-9a-f]{6}$'),
  brand_line char(7) NOT NULL CHECK (brand_line ~ '^#[0-9a-f]{6}$'),
  brand_link char(7) NOT NULL CHECK (brand_link ~ '^#[0-9a-f]{6}$'),
  on_brand char(7) NOT NULL CHECK (on_brand ~ '^#[0-9a-f]{6}$'),
  logo_wide_url text,
  logo_mark_url text,
  logo_mono_url text,
  display_name varchar(60) CHECK (display_name IS NULL OR length(trim(display_name)) > 0),
  adjusted boolean NOT NULL DEFAULT false,
  algo_version smallint NOT NULL DEFAULT 1,
  version int NOT NULL DEFAULT 0,
  updated_by uuid NOT NULL,
  updated_by_kind varchar(10) NOT NULL CHECK (updated_by_kind IN ('TENANT','PLATFORM')),
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE identity.tenant_branding_history (
  tenant_id uuid NOT NULL REFERENCES identity.tenant(id),
  version int NOT NULL,
  snapshot jsonb NOT NULL,
  changed_by uuid NOT NULL,
  changed_by_kind varchar(10) NOT NULL CHECK (changed_by_kind IN ('TENANT','PLATFORM')),
  changed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, version)
);

CREATE INDEX tenant_branding_history_time
  ON identity.tenant_branding_history(tenant_id, changed_at DESC);

ALTER TABLE identity.tenant_branding ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.tenant_branding FORCE ROW LEVEL SECURITY;
ALTER TABLE identity.tenant_branding_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.tenant_branding_history FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_branding_tenant ON identity.tenant_branding
  FOR ALL TO ts_app
  USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_branding_platform ON identity.tenant_branding
  FOR ALL TO ts_platform USING (true) WITH CHECK (true);

CREATE POLICY tenant_branding_history_tenant ON identity.tenant_branding_history
  FOR SELECT TO ts_app
  USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- 租户只能追加/裁剪自己的历史版本，不能改写快照；裁剪由同一写入事务完成。
CREATE POLICY tenant_branding_history_tenant_insert ON identity.tenant_branding_history
  FOR INSERT TO ts_app
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_branding_history_tenant_delete ON identity.tenant_branding_history
  FOR DELETE TO ts_app
  USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_branding_history_platform ON identity.tenant_branding_history
  FOR ALL TO ts_platform USING (true) WITH CHECK (true);

-- 历史由写入事务追加并裁剪到最近 10 版；租户只能读自己的历史。
GRANT SELECT, INSERT, UPDATE ON identity.tenant_branding TO ts_app, ts_platform;
GRANT SELECT, INSERT, DELETE ON identity.tenant_branding_history TO ts_app, ts_platform;

COMMENT ON TABLE identity.tenant_branding IS
  '租户品牌呈现：只存颜色令牌、显示名与 Logo 地址，不含业务数据。';
COMMENT ON TABLE identity.tenant_branding_history IS
  '品牌版本快照；回滚复制历史为新版本，不删除历史。';

-- 品牌写入是租户级动作，不随学校范围；租户管理员默认拥有，其他岗位需显式授予。
ALTER TABLE identity.role_action DROP CONSTRAINT role_action_action_check;
ALTER TABLE identity.role_action ADD CHECK(action IN (
 'ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN',
 'TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN',
 'STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ','STUDENT_ASSIGN','STUDENT_EXPORT',
 'PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH','ENROLLMENT_READ','ENROLLMENT_MAINTAIN','ENROLLMENT_APPROVE',
 'FUNDS_READ','FUNDS_RECEIPT','FUNDS_ALLOCATE','TEACHING_READ','TEACHING_MAINTAIN','ATTENDANCE_RECORD',
 'LESSON_CLOSE','REFUND_READ','REFUND_APPLY','REFUND_PAY','APPROVAL_READ','APPROVAL_MANAGE','APPROVAL_DECIDE',
 'PERIOD_READ','PERIOD_LOCK','MIGRATION_MANAGE','CHANNEL_READ','CHANNEL_MAINTAIN','CUSTOMER_READ',
 'CUSTOMER_MAINTAIN','CUSTOMER_PHONE_READ','ENROLLMENT_DISCOUNT','PERIOD_UNLOCK','CUSTOMER_POOL_READ',
 'CUSTOMER_POOL_MANAGE','CUSTOMER_ASSIGN','TENANT_BRANDING_WRITE'));

ALTER TABLE identity.account_permission DROP CONSTRAINT account_permission_action_check;
ALTER TABLE identity.account_permission ADD CHECK(action IN (
 'ORGANIZATION_READ','ORGANIZATION_MAINTAIN','AUTHORIZATION_MANAGE','EMPLOYEE_READ','EMPLOYEE_MAINTAIN',
 'TEACHER_READ','TEACHER_MAINTAIN','ROOM_READ','ROOM_MAINTAIN','PERSONNEL_READ','PERSONNEL_MAINTAIN',
 'STUDENT_READ','STUDENT_MAINTAIN','STUDENT_SENSITIVE_READ','STUDENT_ASSIGN','STUDENT_EXPORT',
 'PRODUCT_READ','PRODUCT_MAINTAIN','PRICE_PUBLISH','ENROLLMENT_READ','ENROLLMENT_MAINTAIN','ENROLLMENT_APPROVE',
 'FUNDS_READ','FUNDS_RECEIPT','FUNDS_ALLOCATE','TEACHING_READ','TEACHING_MAINTAIN','ATTENDANCE_RECORD',
 'LESSON_CLOSE','REFUND_READ','REFUND_APPLY','REFUND_PAY','APPROVAL_READ','APPROVAL_MANAGE','APPROVAL_DECIDE',
 'PERIOD_READ','PERIOD_LOCK','MIGRATION_MANAGE','CHANNEL_READ','CHANNEL_MAINTAIN','CUSTOMER_READ',
 'CUSTOMER_MAINTAIN','CUSTOMER_PHONE_READ','ENROLLMENT_DISCOUNT','PERIOD_UNLOCK','CUSTOMER_POOL_READ',
 'CUSTOMER_POOL_MANAGE','CUSTOMER_ASSIGN','TENANT_BRANDING_WRITE'));
