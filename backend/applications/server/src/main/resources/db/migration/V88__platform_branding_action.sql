-- 平台品牌代改使用独立动作，不再复用 TENANT_CREATE。
--
-- 迁移时把现有“可开通租户”的运营账号补上品牌动作，保持升级后的既有能力；
-- 之后可以单独撤销品牌权限而不影响开通/重置密码。

ALTER TABLE platform.operator_permission
  DROP CONSTRAINT operator_permission_action_check;

ALTER TABLE platform.operator_permission
  ADD CHECK (action IN (
    'TENANT_READ','TENANT_CREATE','TENANT_SUSPEND','TENANT_RESUME',
    'PROVISIONING_READ','TENANT_BRANDING_WRITE'));

INSERT INTO platform.operator_permission(operator_id, action, effect)
SELECT operator_id, 'TENANT_BRANDING_WRITE', 'ALLOW'
  FROM platform.operator_permission
 WHERE action = 'TENANT_CREATE' AND effect = 'ALLOW'
ON CONFLICT (operator_id, action) DO NOTHING;
