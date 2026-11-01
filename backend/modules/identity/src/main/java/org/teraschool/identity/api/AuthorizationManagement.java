package org.teraschool.identity.api;

import java.util.List;
import java.util.UUID;

/** 账号授权集合的唯一公开入口；版本表示整套直接规则及角色分配。 */
public interface AuthorizationManagement {
  record Option(UUID id, String name) {}

  record RoleOption(UUID id, String name, long version) {}

  record Rule(String action, UUID schoolId, String effect) {}

  record Assignment(UUID roleId, UUID schoolId, Long expectedRoleVersion) {}

  record Replacement(Long expectedVersion, List<Rule> rules, List<Assignment> assignments) {}

  record AccountAuthorization(
      UUID accountId,
      String login,
      boolean active,
      long version,
      List<Rule> rules,
      List<Assignment> assignments,
      CurrentPermissions effective,
      List<Option> schoolLabels,
      List<RoleOption> roleLabels) {}

  List<Option> accounts(String search);

  List<Option> schools(String search);

  List<RoleOption> roles(String search);

  AccountAuthorization get(UUID accountId);

  AccountAuthorization replace(UUID accountId, Replacement replacement);
}
