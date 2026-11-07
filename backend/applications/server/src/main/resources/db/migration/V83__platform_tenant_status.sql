-- 停用/恢复租户：平台角色对 identity.tenant 只有 SELECT。
-- 写入口收在这一个 SECURITY DEFINER 函数里，与开通一致：能做什么恰好等于函数体。
-- 已登录会话不需要逐个删除：租户登录与每次请求的权限查询都要求 identity.tenant.active，
-- 停用后下一次请求即被判定失效并清掉会话。
CREATE FUNCTION platform.set_tenant_active(p_operator uuid, p_tenant uuid, p_active boolean)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, identity, platform
AS $$
DECLARE
  v_current boolean;
BEGIN
  SELECT active INTO v_current
    FROM identity.tenant
   WHERE id = p_tenant
     FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'TENANT_NOT_FOUND';
  END IF;

  -- 重复调用按幂等处理：状态未变就不写第二条审计。
  IF v_current = p_active THEN
    RETURN false;
  END IF;

  UPDATE identity.tenant SET active = p_active WHERE id = p_tenant;

  INSERT INTO platform.audit_event(operator_id, action, tenant_id, changed_fields, trace_id)
  VALUES (
    p_operator,
    CASE WHEN p_active THEN 'TENANT_RESUMED' ELSE 'TENANT_SUSPENDED' END,
    p_tenant,
    'active=' || v_current || '->' || p_active,
    uuidv7());

  RETURN true;
END;
$$;

REVOKE ALL ON FUNCTION platform.set_tenant_active(uuid, uuid, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.set_tenant_active(uuid, uuid, boolean) TO ts_platform;
