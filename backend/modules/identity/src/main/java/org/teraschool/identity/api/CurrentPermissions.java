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
    Set<UUID> manageableSchoolIds,
    /** 租户管理员：覆盖本租户全部学校（含将来新建的），不跨租户。 */
    boolean tenantAdmin,
    /** 首次登录或平台重置后必须先改密，改密前只放行会话自身接口。 */
    boolean passwordChangeRequired) {
  public CurrentPermissions {
    readableSchoolIds = Set.copyOf(readableSchoolIds);
    maintainableSchoolIds = Set.copyOf(maintainableSchoolIds);
    manageableSchoolIds = Set.copyOf(manageableSchoolIds);
  }
}
