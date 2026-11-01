-- 认证版本绑定会话，旧的无版本principal按版本0处理，凭据变化后无法复用。
ALTER TABLE identity.account ADD COLUMN credential_version bigint NOT NULL DEFAULT 0 CHECK(credential_version>=0);
CREATE FUNCTION identity.ensure_credential_version() RETURNS trigger LANGUAGE plpgsql AS $guard$
BEGIN
 IF NEW.credential_version<OLD.credential_version THEN RAISE EXCEPTION 'credential version cannot decrease'; END IF;
 IF (NEW.active IS DISTINCT FROM OLD.active OR NEW.password_hash IS DISTINCT FROM OLD.password_hash)
    AND NEW.credential_version=OLD.credential_version THEN NEW.credential_version:=OLD.credential_version+1; END IF;
 RETURN NEW;
END;
$guard$;
CREATE TRIGGER account_credential_version BEFORE UPDATE OF active,password_hash,credential_version ON identity.account
 FOR EACH ROW EXECUTE FUNCTION identity.ensure_credential_version();
GRANT SELECT(credential_version) ON identity.account TO ts_app;
GRANT INSERT(tenant_id,login,password_hash,active) ON identity.account TO ts_app;
GRANT UPDATE(active,password_hash,credential_version) ON identity.account TO ts_app;
CREATE TABLE audit.account_lifecycle_event (
 id uuid PRIMARY KEY DEFAULT uuidv7(), tenant_id uuid NOT NULL, actor_id uuid NOT NULL,
 account_id uuid NOT NULL, action varchar(40) NOT NULL,
 previous_version bigint, next_version bigint NOT NULL, active boolean NOT NULL,
 trace_id uuid NOT NULL, happened_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE audit.account_lifecycle_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.account_lifecycle_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON audit.account_lifecycle_event
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
GRANT INSERT ON audit.account_lifecycle_event TO ts_app;
ALTER TABLE audit.operation_failure DROP CONSTRAINT operation_failure_action_check;
ALTER TABLE audit.operation_failure ADD CHECK(action IN ('ORGANIZATION_CREATE','ORGANIZATION_RENAME','AUTHORIZATION_REPLACE','ROLE_TEMPLATE_CREATE','ROLE_TEMPLATE_REPLACE','ACCOUNT_CREATE','ACCOUNT_STATUS','ACCOUNT_PASSWORD_RESET','ACCOUNT_SESSIONS_REVOKE'));
