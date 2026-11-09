-- REV-022：品牌 Logo 资产。
--
-- 对象内容放私有对象存储，数据库只保存按版本递增的对象键、类型与大小；
-- 旧版本 URL 仍可读取，品牌历史回滚时不会因为覆盖同名文件而丢失旧 Logo。

CREATE TABLE identity.tenant_branding_logo (
  tenant_id uuid NOT NULL REFERENCES identity.tenant(id),
  kind varchar(10) NOT NULL CHECK (kind IN ('WIDE','MARK','MONO')),
  version int NOT NULL CHECK (version > 0),
  object_key text NOT NULL,
  content_type varchar(50) NOT NULL,
  byte_size int NOT NULL CHECK (byte_size > 0 AND byte_size <= 204800),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, kind, version),
  UNIQUE (tenant_id, object_key)
);

ALTER TABLE identity.tenant_branding_logo ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.tenant_branding_logo FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_branding_logo_tenant ON identity.tenant_branding_logo
  FOR SELECT TO ts_app
  USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_branding_logo_tenant_insert ON identity.tenant_branding_logo
  FOR INSERT TO ts_app
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_branding_logo_platform ON identity.tenant_branding_logo
  FOR SELECT TO ts_platform USING (true);

GRANT SELECT, INSERT ON identity.tenant_branding_logo TO ts_app, ts_platform;
