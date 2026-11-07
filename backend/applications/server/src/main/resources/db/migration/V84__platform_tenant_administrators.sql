-- 平台查看/重置租户管理员凭据（DEC-024 第 7 条）。
--
-- 平台角色对 identity.account 没有读写权限：列表与改密都收在受控函数里，
-- 平台能做的事恰好等于这两个函数体，而不是把账号表交出去。

-- 列出某租户的租户管理员（登录名、状态）。只暴露定位所需的字段；identity.account 没有创建时间列。
CREATE FUNCTION platform.tenant_administrators(p_tenant uuid)
RETURNS TABLE(login varchar, active boolean)
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, identity, platform
AS $$
  SELECT a.login, a.active
    FROM identity.account a
   WHERE a.tenant_id = p_tenant AND a.tenant_admin
   ORDER BY a.login;
$$;

REVOKE ALL ON FUNCTION platform.tenant_administrators(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.tenant_administrators(uuid) TO ts_platform;

-- 重置租户管理员密码：置回强制改密并递增凭据版本，使该账号既有会话立即失效。
CREATE FUNCTION platform.reset_tenant_admin_password(
  p_operator uuid,
  p_tenant uuid,
  p_login varchar,
  p_new_hash varchar)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, identity, platform
AS $$
DECLARE
  v_account uuid;
BEGIN
  SELECT a.id INTO v_account
    FROM identity.account a
   WHERE a.tenant_id = p_tenant AND a.login = p_login AND a.tenant_admin
     FOR UPDATE;
  IF NOT FOUND THEN
    RETURN false;
  END IF;

  UPDATE identity.account
     SET password_hash = p_new_hash,
         password_change_required = true,
         credential_version = credential_version + 1
   WHERE id = v_account;

  INSERT INTO platform.audit_event(operator_id, action, tenant_id, changed_fields, trace_id)
  VALUES (p_operator, 'TENANT_ADMIN_PASSWORD_RESET', p_tenant,
          'login=' || p_login, uuidv7());

  RETURN true;
END;
$$;

REVOKE ALL ON FUNCTION platform.reset_tenant_admin_password(uuid,uuid,varchar,varchar) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.reset_tenant_admin_password(uuid,uuid,varchar,varchar) TO ts_platform;
