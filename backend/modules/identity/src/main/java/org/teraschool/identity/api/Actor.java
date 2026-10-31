package org.teraschool.identity.api;

import java.util.Set;
import java.util.UUID;

/** 当前请求复核后的主体；学校范围为空表示无任何学校权限。 */
public record Actor(UUID accountId, UUID tenantId, Set<UUID> schools) {
  public Actor {
    schools = Set.copyOf(schools);
  }
}
