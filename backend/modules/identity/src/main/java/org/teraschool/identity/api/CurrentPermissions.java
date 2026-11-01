package org.teraschool.identity.api;

import java.util.Set;
import java.util.UUID;

/** 当前有效功能和学校范围；只代表本批组织动作，不冒充完整权限目录。 */
public record CurrentPermissions(
    UUID accountId,
    UUID tenantId,
    boolean canReadOrganizations,
    boolean canMaintainOrganizations,
    Set<UUID> readableSchoolIds,
    Set<UUID> maintainableSchoolIds,
    boolean canManageAuthorizations,
    Set<UUID> manageableSchoolIds) {
  public CurrentPermissions {
    readableSchoolIds = Set.copyOf(readableSchoolIds);
    maintainableSchoolIds = Set.copyOf(maintainableSchoolIds);
    manageableSchoolIds = Set.copyOf(manageableSchoolIds);
  }
}
