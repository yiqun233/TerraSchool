package org.teraschool.identity.internal;

import java.util.*;
import org.teraschool.identity.api.CurrentPermissions;

/** 网页、直接调用和授权修改前后检查共用同一计算，不维护第二套ALLOW/DENY算法。 */
final class PermissionRules {
  static final Set<String> ACTIONS =
      Set.of(
          "ORGANIZATION_READ",
          "ORGANIZATION_MAINTAIN",
          "AUTHORIZATION_MANAGE",
          "EMPLOYEE_READ",
          "EMPLOYEE_MAINTAIN",
          "TEACHER_READ",
          "TEACHER_MAINTAIN",
          "ROOM_READ",
          "ROOM_MAINTAIN",
          "PERSONNEL_READ",
          "PERSONNEL_MAINTAIN");
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
    SELECT a.id,a.tenant_id,p.action,p.school_id,p.effect,a.credential_version
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

  static CurrentPermissions calculate(
      UUID account, UUID tenant, boolean active, List<Entry> rules) {
    var read = new Decision();
    var maintain = new Decision();
    var manage = new Decision();
    if (active)
      for (var rule : rules) {
        if ("ORGANIZATION_READ".equals(rule.action())) read.accept(rule);
        else if ("ORGANIZATION_MAINTAIN".equals(rule.action())) maintain.accept(rule);
        else if ("AUTHORIZATION_MANAGE".equals(rule.action())) manage.accept(rule);
      }
    var readable = read.schools();
    var editable = maintain.schools();
    editable.retainAll(readable);
    return new CurrentPermissions(
        account,
        tenant,
        read.enabled(),
        read.enabled() && maintain.enabled(),
        readable,
        editable,
        manage.enabled(),
        manage.schools());
  }

  static org.teraschool.identity.api.Actor resource(
      UUID account, UUID tenant, List<Entry> rules, String kind, boolean write) {
    if (!Set.of("EMPLOYEE", "TEACHER", "ROOM", "PERSONNEL").contains(Objects.toString(kind, "")))
      throw new IllegalArgumentException("无效资源类型");
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
