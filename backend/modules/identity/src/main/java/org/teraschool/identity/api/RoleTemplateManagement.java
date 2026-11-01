package org.teraschool.identity.api;

import java.util.List;
import java.util.UUID;

/** 模板定义维护和账号分配共享授权保护；模板code创建后固定。 */
public interface RoleTemplateManagement {
  record RoleAction(String action, String effect) {}

  record RoleSummary(UUID id, String code, String name, boolean active, long version) {}

  record RoleTemplate(
      UUID id,
      String code,
      String name,
      boolean active,
      long version,
      List<RoleAction> actions,
      int assignedAccounts) {}

  record CreateRole(UUID requestId, String code, String name, List<RoleAction> actions) {}

  record RoleChange(Long expectedVersion, String name, Boolean active, List<RoleAction> actions) {}

  List<RoleSummary> listRoles(String search, boolean includeInactive);

  RoleTemplate getRole(UUID id);

  RoleTemplate createRole(CreateRole input);

  RoleTemplate replaceRole(UUID id, RoleChange input);
}
