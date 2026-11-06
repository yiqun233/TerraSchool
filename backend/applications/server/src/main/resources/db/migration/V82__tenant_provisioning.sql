-- REV-020：租户管理员标记与开通编排（DEC-024）。
--
-- 开通只产出「租户 + 一个租户管理员账号」，不建学校：签约时通常还不知道客户在哪个校区开课。
-- 为了让新租户能建出第一所学校，账号需要一个租户管理员标记——现有“覆盖全部学校”的判断
-- 在没有任何学校时会返回否，会把新租户卡死。

ALTER TABLE identity.account ADD COLUMN tenant_admin boolean NOT NULL DEFAULT false;
ALTER TABLE identity.account ADD COLUMN password_change_required boolean NOT NULL DEFAULT false;

-- identity.account 的读取是列级授权：新增列必须显式授权，否则权限快照查询会整条失败。
GRANT SELECT(tenant_admin, password_change_required) ON identity.account TO ts_app, ts_identity;
GRANT UPDATE(password_change_required) ON identity.account TO ts_app;

-- 租户列表需要开通时间；既有租户回填为迁移时刻（无法追溯真实开通时间，只作展示）。
ALTER TABLE identity.tenant ADD COLUMN created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- 开通记录：请求身份 + 参数摘要用于幂等；失败也留一行，便于解释与重试。
CREATE TABLE platform.provisioning_request (
  id uuid PRIMARY KEY DEFAULT uuidv7(),
  operator_id uuid NOT NULL REFERENCES platform.operator(id),
  request_id uuid NOT NULL,
  fingerprint char(64) NOT NULL,
  status varchar(20) NOT NULL CHECK (status IN ('ACTIVE','FAILED')),
  tenant_id uuid,
  account_id uuid,
  error_code varchar(60),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at timestamptz,
  UNIQUE (operator_id, request_id)
);
GRANT SELECT, INSERT, UPDATE ON platform.provisioning_request TO ts_platform;

-- 开通编排放在一个 SECURITY DEFINER 函数里：
-- 平台角色没有 identity.account 的写权限，业务表更是一张都没有；
-- 这个函数由迁移角色拥有，只做“建租户 + 建管理员 + 记开通 + 记审计”这一件事。
CREATE FUNCTION platform.provision_tenant(
  p_operator uuid,
  p_request_id uuid,
  p_fingerprint char(64),
  p_tenant_name varchar,
  p_admin_login varchar,
  p_admin_hash varchar,
  p_admin_name varchar)
RETURNS TABLE(tenant_id uuid, account_id uuid)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, identity, platform
AS $$
DECLARE
  v_existing platform.provisioning_request;
  v_tenant uuid;
  v_account uuid;
BEGIN
  IF p_tenant_name IS NULL OR length(trim(p_tenant_name)) = 0 THEN
    RAISE EXCEPTION 'TENANT_NAME_REQUIRED';
  END IF;
  IF p_admin_login IS NULL OR length(trim(p_admin_login)) < 3 THEN
    RAISE EXCEPTION 'ADMIN_LOGIN_REQUIRED';
  END IF;

  SELECT * INTO v_existing FROM platform.provisioning_request
   WHERE operator_id = p_operator AND request_id = p_request_id;
  IF FOUND THEN
    IF v_existing.fingerprint <> p_fingerprint THEN
      RAISE EXCEPTION 'IDEMPOTENCY_MISMATCH';
    END IF;
    RETURN QUERY SELECT v_existing.tenant_id, v_existing.account_id;
    RETURN;
  END IF;

  IF EXISTS (SELECT 1 FROM identity.tenant WHERE lower(name) = lower(trim(p_tenant_name))) THEN
    RAISE EXCEPTION 'TENANT_NAME_TAKEN';
  END IF;

  INSERT INTO identity.tenant(id, name) VALUES (uuidv7(), trim(p_tenant_name))
  RETURNING id INTO v_tenant;

  INSERT INTO identity.account(tenant_id, login, password_hash, tenant_admin, password_change_required)
  VALUES (v_tenant, trim(p_admin_login), p_admin_hash, true, true)
  RETURNING id INTO v_account;

  INSERT INTO platform.provisioning_request(
      operator_id, request_id, fingerprint, status, tenant_id, account_id, finished_at)
  VALUES (p_operator, p_request_id, p_fingerprint, 'ACTIVE', v_tenant, v_account, now());

  INSERT INTO platform.audit_event(operator_id, action, tenant_id, changed_fields, trace_id)
  VALUES (p_operator, 'TENANT_PROVISIONED', v_tenant, 'admin=' || trim(p_admin_login), uuidv7());

  RETURN QUERY SELECT v_tenant, v_account;
END;
$$;

REVOKE ALL ON FUNCTION platform.provision_tenant(uuid,uuid,char,varchar,varchar,varchar,varchar)
  FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.provision_tenant(uuid,uuid,char,varchar,varchar,varchar,varchar)
  TO ts_platform;
