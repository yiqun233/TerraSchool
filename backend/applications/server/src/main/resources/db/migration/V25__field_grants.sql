-- 字段级权限：在"功能动作"之外，按字段授予敏感字段的读取权。
-- 默认不授予；持有 STUDENT_SENSITIVE_READ 的账号按既有语义视为拥有该学校全部字段（保持兼容）。
CREATE TABLE identity.field_grant (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id uuid NOT NULL REFERENCES identity.tenant(id),
  account_id uuid NOT NULL,
  school_id uuid NOT NULL,
  school_kind varchar(20) NOT NULL DEFAULT 'SCHOOL' CHECK(school_kind='SCHOOL'),
  -- 字段键由各域定义，例如 STUDENT_PHONE / STUDENT_CERT / STUDENT_BIRTHDAY / STUDENT_EMAIL / CONTACT_PHONE
  field_key varchar(40) NOT NULL,
  effect varchar(8) NOT NULL DEFAULT 'ALLOW' CHECK(effect IN ('ALLOW','DENY')),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id,account_id,school_id,field_key),
  FOREIGN KEY(tenant_id,school_id,school_kind) REFERENCES organization.unit(tenant_id,id,kind),
  FOREIGN KEY(account_id) REFERENCES identity.account(id)
);


-- 兼容：已有 STUDENT_SENSITIVE_READ 的账号，按学校补齐五个学员字段的显式授予。
INSERT INTO identity.field_grant(tenant_id,account_id,school_id,field_key,effect)
SELECT p.tenant_id,p.account_id,p.school_id,f.field_key,'ALLOW'
FROM identity.account_permission p
CROSS JOIN (VALUES ('STUDENT_PHONE'),('STUDENT_CERT'),('STUDENT_BIRTHDAY'),('STUDENT_EMAIL'),('CONTACT_PHONE')) AS f(field_key)
WHERE p.action='STUDENT_SENSITIVE_READ' AND p.effect='ALLOW'
ON CONFLICT (tenant_id,account_id,school_id,field_key) DO NOTHING;

ALTER TABLE identity.field_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.field_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON identity.field_grant
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

GRANT SELECT,INSERT,UPDATE,DELETE ON identity.field_grant TO ts_app;
