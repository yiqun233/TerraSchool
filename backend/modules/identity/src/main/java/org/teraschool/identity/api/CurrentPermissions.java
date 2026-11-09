package org.teraschool.identity.api;

import java.util.Set;
import java.util.UUID;

/** 当前有效功能和学校范围；grantedActions 只表示功能资格，学校范围仍看各动作集合。 */
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
    boolean passwordChangeRequired,
    /** 租户级品牌写入：不随学校范围；租户管理员默认拥有。 */
    boolean canWriteBranding,
    /**
     * 功能已开启的动作码，来自 {@link PermissionCatalog}。
     *
     * <p>只用于界面显隐与“我的权限”呈现，不携带学校范围；业务授权仍由各模块按动作重新校验。
     */
    Set<String> grantedActions) {
  public CurrentPermissions {
    readableSchoolIds = Set.copyOf(readableSchoolIds);
    maintainableSchoolIds = Set.copyOf(maintainableSchoolIds);
    manageableSchoolIds = Set.copyOf(manageableSchoolIds);
    grantedActions = Set.copyOf(grantedActions);
  }
}
