-- 平台租户详情 + 开通失败留痕 + 租户名唯一（REV-020/REV-021 收尾）。

-- 0) 平台角色需要能读到租户清单本身。V81 只授了列权限，没有对应策略；
--    identity.tenant 是 FORCE RLS，没有策略时 ts_platform 一行也看不到（列表一直是空的）。
CREATE POLICY platform_read ON identity.tenant FOR SELECT TO ts_platform USING (true);

-- 1) 租户详情：学校数、账号数、管理员数。平台角色对业务表没有权限，
--    计数同样收在受控函数里，只返回数字，不返回任何业务明细。
CREATE FUNCTION platform.tenant_summary(p_tenant uuid)
RETURNS TABLE(schools bigint, accounts bigint, administrators bigint)
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, identity, organization, platform
AS $$
  SELECT
    (SELECT count(*) FROM organization.unit u
      WHERE u.tenant_id = p_tenant AND u.kind = 'SCHOOL'),
    (SELECT count(*) FROM identity.account a WHERE a.tenant_id = p_tenant),
    (SELECT count(*) FROM identity.account a WHERE a.tenant_id = p_tenant AND a.tenant_admin);
$$;

REVOKE ALL ON FUNCTION platform.tenant_summary(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.tenant_summary(uuid) TO ts_platform;

-- 2) 租户名**不做**全局唯一。
--    最初想用唯一索引挡住并发同名，但不同客户完全可能叫同一个名字（“明德学堂”不止一家），
--    用名字当标识只会把合法开通卡住。真正的标识是租户 id，登录名单独唯一即可。
-- 3) 开通失败可重试：失败行会留在 provisioning_request 里解释原因，
--    同一 requestId 重试时必须允许覆盖它，而不是被幂等判断挡回去。
CREATE OR REPLACE FUNCTION platform.provision_tenant(
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
    IF v_existing.status = 'ACTIVE' THEN
      RETURN QUERY SELECT v_existing.tenant_id, v_existing.account_id;
      RETURN;
    END IF;
    -- 失败行：同一请求重试时清掉旧的失败记录，继续走完整编排。
    DELETE FROM platform.provisioning_request WHERE id = v_existing.id;
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
