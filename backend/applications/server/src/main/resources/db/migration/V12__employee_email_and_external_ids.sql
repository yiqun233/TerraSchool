CREATE TABLE organization.employee_email_config (
 tenant_id uuid NOT NULL,school_id uuid NOT NULL,employee_id uuid PRIMARY KEY,account_id uuid NOT NULL,
 smtp_host varchar(200) NOT NULL DEFAULT '',smtp_user varchar(200) NOT NULL DEFAULT '',
 sender_address varchar(200) NOT NULL DEFAULT '',credential_nonce bytea,credential_ciphertext bytea,
 credential_key_version integer,
 CHECK ((credential_nonce IS NULL AND credential_ciphertext IS NULL AND credential_key_version IS NULL)
    OR (credential_nonce IS NOT NULL AND credential_ciphertext IS NOT NULL AND credential_key_version IS NOT NULL
        AND octet_length(credential_nonce)=12 AND octet_length(credential_ciphertext)>=16 AND credential_key_version=1)),
 FOREIGN KEY(tenant_id,school_id,employee_id) REFERENCES organization.employee(tenant_id,school_id,id),
 FOREIGN KEY(tenant_id,account_id) REFERENCES identity.account(tenant_id,id)
);
CREATE TABLE organization.personnel_external_profile (
 tenant_id uuid NOT NULL,school_id uuid NOT NULL,employee_id uuid PRIMARY KEY,profile jsonb NOT NULL,
 FOREIGN KEY(tenant_id,school_id,employee_id) REFERENCES organization.employee(tenant_id,school_id,id)
);
-- 主密钥是部署级资源；该记录只含不可逆校验值，不含租户或凭据。
CREATE TABLE infra.mail_key_verifier (
 id smallint PRIMARY KEY DEFAULT 1 CHECK(id=1), verifier bytea NOT NULL CHECK(octet_length(verifier)=32)
);
GRANT USAGE ON SCHEMA infra TO ts_app;
GRANT SELECT,INSERT ON infra.mail_key_verifier TO ts_app;
DO $policies$
DECLARE tab text;
BEGIN
 FOR tab IN SELECT unnest(ARRAY['employee_email_config','personnel_external_profile']) LOOP
  EXECUTE format('ALTER TABLE organization.%I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE organization.%I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_scope ON organization.%I USING(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
  EXECUTE format('GRANT SELECT,INSERT ON organization.%I TO ts_app',tab);
 END LOOP;
END;
$policies$;
GRANT UPDATE ON organization.employee_email_config,organization.personnel_external_profile TO ts_app;
ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE','ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE','ACCOUNT_CREATE','ACCOUNT_STATUS','ACCOUNT_PASSWORD_RESET','ACCOUNT_SESSIONS_REVOKE','ORGANIZATION_SCHOOL_CREATE','ORGANIZATION_SCHOOL_UPDATE','EMPLOYEE_CREATE','EMPLOYEE_UPDATE','TEACHER_CREATE','TEACHER_UPDATE','ROOM_CREATE','ROOM_UPDATE','TEACHER_QUALIFICATION_UPDATE','EMPLOYEE_ACCOUNT_BIND','PERSONNEL_UPDATE','PERSONNEL_ATTACHMENT_CREATE','PERSONNEL_ATTACHMENT_READ','PERSONNEL_ATTACHMENT_REMOVE','EMPLOYEE_EMAIL_UPDATE','PERSONNEL_EXTERNAL_UPDATE'));
