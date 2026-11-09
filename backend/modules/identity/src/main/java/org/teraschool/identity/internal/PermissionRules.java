package org.teraschool.identity.internal;

import java.util.*;
import org.teraschool.identity.api.ActionGrant;
import org.teraschool.identity.api.CurrentPermissions;
import org.teraschool.identity.api.PermissionCatalog;

/** 网页、直接调用和授权修改前后检查共用同一计算，不维护第二套ALLOW/DENY算法。 */
final class PermissionRules {
  /** 目录只在这里登记一次；界面与授权校验都从 {@link PermissionCatalog} 派生。 */
  static final Set<String> ACTIONS = PermissionCatalog.CODES;

  /** 租户管理员默认开通的动作；与 calculate 的四个布尔保持一致。 */
  static final Set<String> TENANT_ADMIN_ACTIONS =
      Set.of(
          "ORGANIZATION_READ",
          "ORGANIZATION_MAINTAIN",
          "AUTHORIZATION_MANAGE",
          "TENANT_BRANDING_WRITE");

  static final String EXPANDED =
      """
    SELECT p.account_id,p.tenant_id,p.action,p.school_id,p.effect FROM identity.account_permission p
    UNION ALL
    SELECT m.account_id,m.tenant_id,g.action,m.school_id,g.effect FROM identity.account_role m
      JOIN identity.permission_role r ON r.id=m.role_id AND r.tenant_id=m.tenant_id AND r.active
      JOIN identity.role_action g ON g.role_id=r.id AND g.tenant_id=r.tenant_id
    """;
  static final String CURRENT =
      """
    SELECT a.id,a.tenant_id,p.action,p.school_id,p.effect,a.credential_version,a.tenant_admin,a.password_change_required
    FROM identity.account a JOIN identity.tenant t ON t.id=a.tenant_id
    LEFT JOIN LATERAL (
    """
          + EXPANDED
          + """
    ) p ON p.account_id=a.id AND p.tenant_id=a.tenant_id
    WHERE a.id=? AND a.active AND t.active
    """;

  record Entry(String action, UUID school, String effect) {}

  private static class Decision {
    boolean globalAllow, globalDeny;
    final Set<UUID> allows = new HashSet<>(), denies = new HashSet<>();

    void accept(Entry rule) {
      boolean allow = "ALLOW".equals(rule.effect());
      if (rule.school() == null) {
        if (allow) globalAllow = true;
        else globalDeny = true;
      } else if (allow) allows.add(rule.school());
      else denies.add(rule.school());
    }

    boolean enabled() {
      return !globalDeny && (globalAllow || !allows.isEmpty());
    }

    Set<UUID> schools() {
      var result = new HashSet<>(allows);
      result.removeAll(denies);
      if (!enabled()) result.clear();
      return result;
    }
  }

  /** 按目录为每个动作各建一份决策；目录外的历史取值不参与计算，也不影响其他动作。 */
  private static Map<String, Decision> decisions(List<Entry> rules) {
    var result = new LinkedHashMap<String, Decision>();
    for (var action : PermissionCatalog.ACTIONS) result.put(action.code(), new Decision());
    for (var rule : rules) {
      var decision = result.get(rule.action());
      if (decision != null) decision.accept(rule);
    }
    return result;
  }

  static CurrentPermissions calculate(
      UUID account,
      UUID tenant,
      boolean active,
      List<Entry> rules,
      boolean tenantAdmin,
      boolean passwordChangeRequired) {
    var decisions = active ? decisions(rules) : decisions(List.of());
    var read = decisions.get("ORGANIZATION_READ");
    var maintain = decisions.get("ORGANIZATION_MAINTAIN");
    var manage = decisions.get("AUTHORIZATION_MANAGE");
    var branding = decisions.get("TENANT_BRANDING_WRITE");
    var readable = read.schools();
    var editable = maintain.schools();
    editable.retainAll(readable);
    // 功能资格只表示“这个动作开了”，学校范围仍由显式学校授权决定；
    // “覆盖全部学校”由 SchoolProvisioner 结合 tenantAdmin 单独判断。
    var granted = new LinkedHashSet<String>();
    decisions.forEach(
        (action, decision) -> {
          if (decision.enabled()) granted.add(action);
        });
    if (tenantAdmin) granted.addAll(TENANT_ADMIN_ACTIONS);
    return new CurrentPermissions(
        account,
        tenant,
        read.enabled() || tenantAdmin,
        (read.enabled() && maintain.enabled()) || tenantAdmin,
        readable,
        editable,
        manage.enabled() || tenantAdmin,
        manage.schools(),
        tenantAdmin,
        passwordChangeRequired,
        branding.enabled() || tenantAdmin,
        granted);
  }

  /**
   * 逐动作的有效结果，用于“我的权限”只读呈现。
   *
   * <p>与 {@link #calculate} 共用同一决策过程，不另写一套合并规则；只返回功能已开启的动作。
   */
  static List<ActionGrant> grants(List<Entry> rules, boolean tenantAdmin) {
    var decisions = decisions(rules);
    var result = new ArrayList<ActionGrant>();
    for (var action : PermissionCatalog.ACTIONS) {
      var decision = decisions.get(action.code());
      boolean enabled =
          decision.enabled() || (tenantAdmin && TENANT_ADMIN_ACTIONS.contains(action.code()));
      if (!enabled) continue;
      var schools = decision.schools();
      result.add(new ActionGrant(action.code(), schools.isEmpty(), schools));
    }
    return List.copyOf(result);
  }

  /**
   * 一次快照同时给出查看、维护与敏感字段范围。
   *
   * <p>敏感字段范围只有与查看范围相交才生效，避免出现“能看敏感字段但不能看这条档案”的矛盾授权。
   */
  static org.teraschool.identity.api.ResourceScope scope(
      UUID account, UUID tenant, List<Entry> rules, String kind) {
    if (!Set.of(
            "EMPLOYEE",
            "TEACHER",
            "ROOM",
            "PERSONNEL",
            "STUDENT",
            "PRODUCT",
            "ENROLLMENT",
            "FUNDS",
            "TEACHING",
            "APPROVAL",
            "PERIOD",
            "MIGRATION",
            "CHANNEL",
            "CUSTOMER")
        .contains(Objects.toString(kind, ""))) throw new IllegalArgumentException("无效资源类型");
    var read = new Decision();
    var maintain = new Decision();
    var sensitive = new Decision();
    for (var rule : rules) {
      if ((kind + "_READ").equals(rule.action())) read.accept(rule);
      else if ((kind + "_MAINTAIN").equals(rule.action())) maintain.accept(rule);
      else if ((kind + "_SENSITIVE_READ").equals(rule.action())) sensitive.accept(rule);
    }
    if (!read.enabled())
      throw new org.springframework.security.access.AccessDeniedException("无资源读取权限");
    var readable = read.schools();
    var maintainable = new HashSet<>(maintain.schools());
    maintainable.retainAll(readable);
    var sensitiveSchools = new HashSet<>(sensitive.schools());
    sensitiveSchools.retainAll(readable);
    return new org.teraschool.identity.api.ResourceScope(
        account, tenant, readable, maintainable, sensitiveSchools);
  }

  /**
   * 任意具名动作的学校范围。
   *
   * <p>用于“发布定价”这类不能与普通维护合并的动作：调用方必须持有该动作，范围与查看范围无关。
   */
  static org.teraschool.identity.api.Actor action(
      UUID account, UUID tenant, List<Entry> rules, String action) {
    var decision = new Decision();
    for (var rule : rules) if (action.equals(rule.action())) decision.accept(rule);
    if (!decision.enabled())
      throw new org.springframework.security.access.AccessDeniedException("无该动作权限");
    return new org.teraschool.identity.api.Actor(account, tenant, decision.schools());
  }

  static org.teraschool.identity.api.Actor resource(
      UUID account, UUID tenant, List<Entry> rules, String kind, boolean write) {
    if (!Set.of(
            "EMPLOYEE",
            "TEACHER",
            "ROOM",
            "PERSONNEL",
            "STUDENT",
            "PRODUCT",
            "ENROLLMENT",
            "FUNDS",
            "TEACHING",
            "APPROVAL",
            "PERIOD",
            "MIGRATION",
            "CHANNEL",
            "CUSTOMER")
        .contains(Objects.toString(kind, ""))) throw new IllegalArgumentException("无效资源类型");
    var read = new Decision();
    var maintain = new Decision();
    for (var rule : rules) {
      if ((kind + "_READ").equals(rule.action())) read.accept(rule);
      else if ((kind + "_MAINTAIN").equals(rule.action())) maintain.accept(rule);
    }
    if (!read.enabled())
      throw new org.springframework.security.access.AccessDeniedException("无资源读取权限");
    var schools = read.schools();
    if (write) {
      schools.retainAll(maintain.schools());
    }
    return new org.teraschool.identity.api.Actor(account, tenant, schools);
  }
}
