-- 批次 A：平台运营可观测（总览 / 开通记录 / 审计日志）。
--
-- 本轮只做“看”：不新增任何业务表授权，ts_platform 仍读不到 teaching/enrollment/funds/archive
-- （DEC-024）。变化全部落在 platform schema 内，加一个只返回计数的受控函数。

-- 1) 开通记录要能解释“是谁失败了”。
--    原来只存 fingerprint 与错误码，失败行没有 tenant_id，页面既显示不出机构名，
--    也核对不了提交的登录名；补两列由开通函数和失败留痕写入，历史行保持为空。
ALTER TABLE platform.provisioning_request ADD COLUMN tenant_name varchar(100);
ALTER TABLE platform.provisioning_request ADD COLUMN admin_login varchar(60);

-- 2) 开通成功时把可读参数一并落库。函数签名不变，只在 INSERT 里补两列，
--    这样开通记录页不必再靠 tenant_id 反查（失败行本来就没有 tenant_id）。
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
      operator_id, request_id, fingerprint, status, tenant_id, account_id,
      tenant_name, admin_login, finished_at)
  VALUES (p_operator, p_request_id, p_fingerprint, 'ACTIVE', v_tenant, v_account,
          trim(p_tenant_name), trim(p_admin_login), now());

  INSERT INTO platform.audit_event(operator_id, action, tenant_id, changed_fields, trace_id)
  VALUES (p_operator, 'TENANT_PROVISIONED', v_tenant, 'admin=' || trim(p_admin_login), uuidv7());

  RETURN QUERY SELECT v_tenant, v_account;
END;
$$;

REVOKE ALL ON FUNCTION platform.provision_tenant(uuid,uuid,char,varchar,varchar,varchar,varchar)
  FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.provision_tenant(uuid,uuid,char,varchar,varchar,varchar,varchar)
  TO ts_platform;

-- 3) 审计查询使用独立动作，不与“看租户”复用：
--    能看租户不等于能看全平台操作轨迹（谁停用了哪个租户、谁重置了密码）。
ALTER TABLE platform.operator_permission DROP CONSTRAINT operator_permission_action_check;

ALTER TABLE platform.operator_permission
  ADD CHECK (action IN (
    'TENANT_READ','TENANT_CREATE','TENANT_SUSPEND','TENANT_RESUME',
    'PROVISIONING_READ','TENANT_BRANDING_WRITE','PLATFORM_AUDIT_READ'));

-- 升级时给现有“能看租户”的账号补上审计读取，保持升级后既有能力；
-- 之后可以单独撤销审计权限而不影响租户列表。
INSERT INTO platform.operator_permission(operator_id, action, effect)
SELECT operator_id, 'PLATFORM_AUDIT_READ', 'ALLOW'
  FROM platform.operator_permission
 WHERE action = 'TENANT_READ' AND effect = 'ALLOW'
ON CONFLICT (operator_id, action) DO NOTHING;

-- 审计页按动作筛选；现有两个索引按租户、按操作者，缺按动作。
CREATE INDEX audit_event_action ON platform.audit_event(action, happened_at);

-- 4) 总览的跨租户计数。平台角色对业务表没有权限，计数收在受控函数里，
--    只返回两个数字，不返回任何业务明细。
CREATE FUNCTION platform.tenant_totals()
RETURNS TABLE(schools bigint, accounts bigint)
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, identity, organization, platform
AS $$
  SELECT
    (SELECT count(*) FROM organization.unit u WHERE u.kind = 'SCHOOL'),
    (SELECT count(*) FROM identity.account a);
$$;

REVOKE ALL ON FUNCTION platform.tenant_totals() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.tenant_totals() TO ts_platform;
