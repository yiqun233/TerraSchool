package org.teraschool.identity.api;

import java.util.Set;
import java.util.UUID;

/**
 * 某类资源在一次快照中的有效范围。
 *
 * <p>查看范围决定能看到哪些记录；维护范围已与该查看范围相交；敏感字段范围再与查看范围相交，因此它是 “可以对哪些学校返回完整敏感字段”，不是额外的功能授权。
 */
public record ResourceScope(
    UUID accountId,
    UUID tenantId,
    Set<UUID> readableSchoolIds,
    Set<UUID> maintainableSchoolIds,
    Set<UUID> sensitiveSchoolIds) {
  public ResourceScope {
    readableSchoolIds = Set.copyOf(readableSchoolIds);
    maintainableSchoolIds = Set.copyOf(maintainableSchoolIds);
    sensitiveSchoolIds = Set.copyOf(sensitiveSchoolIds);
  }

  /** 维护与敏感字段都要能定位学校，单独判断可减少调用方重复计算。 */
  public Actor reader() {
    return new Actor(accountId, tenantId, readableSchoolIds);
  }

  public Actor maintainer() {
    return new Actor(accountId, tenantId, maintainableSchoolIds);
  }
}
