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
          "PERSONNEL_MAINTAIN",
          "STUDENT_READ",
          "STUDENT_MAINTAIN",
          "STUDENT_SENSITIVE_READ",
          "PRODUCT_READ",
          "PRODUCT_MAINTAIN",
          "PRICE_PUBLISH",
          "ENROLLMENT_READ",
          "ENROLLMENT_MAINTAIN",
          "ENROLLMENT_APPROVE",
          "FUNDS_READ",
          "FUNDS_RECEIPT",
          "FUNDS_ALLOCATE",
          "TEACHING_READ",
          "TEACHING_MAINTAIN",
          "ATTENDANCE_RECORD",
          "LESSON_CLOSE",
          "APPROVAL_READ",
          "APPROVAL_MANAGE",
          "APPROVAL_DECIDE");
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
            "APPROVAL")
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
            "APPROVAL")
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
