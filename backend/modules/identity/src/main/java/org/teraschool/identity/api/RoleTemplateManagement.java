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

  /** 预设模板预览：动作集合只读，供“从模板生成”页面展示。 */
  record StartingTemplate(
      String code, String name, String purpose, int actionCount, List<String> actions) {
    public StartingTemplate {
      actions = List.copyOf(actions);
    }
  }

  /** 从预设模板生成角色；name/code 为空时使用模板默认值。 */
  record GenerateFromTemplate(UUID requestId, String templateCode, String name, String code) {}

  record RoleChange(Long expectedVersion, String name, Boolean active, List<RoleAction> actions) {}

  List<RoleSummary> listRoles(String search, boolean includeInactive);

  RoleTemplate getRole(UUID id);

  RoleTemplate createRole(CreateRole input);

  List<StartingTemplate> startingTemplates();

  RoleTemplate generateFromTemplate(GenerateFromTemplate input);

  RoleTemplate replaceRole(UUID id, RoleChange input);
}
