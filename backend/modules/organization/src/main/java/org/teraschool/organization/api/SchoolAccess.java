package org.teraschool.organization.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 他域读取学校身份与状态、并共用学校生命周期锁的最小契约。
 *
 * <p>它只回答“这所学校是否存在、是否启用”，不代替调用方自己的功能授权和数据范围；学校停用、授权替换和 需要拒绝业务写入的操作应当先取同一把机构锁，再在锁内调用 {@link
 * #require(UUID)}。
 */
public interface SchoolAccess {
  record School(UUID id, String name, boolean active) {}

  /** 与学校停用、授权写入共用的机构级事务锁；必须在已设置租户、READ COMMITTED 的业务事务内调用。 */
  void lockTenant(UUID tenantId);

  /** 锁内读取学校；不存在或不属于当前租户时拒绝。 */
  School require(UUID schoolId);

  /** 校验校区确实属于该学校；校区不存在、已停用或不属于该校时拒绝。 */
  void requireCampus(UUID schoolId, UUID campusId);

  /** 列表展示用的学校名称，只返回当前租户可见的学校。 */
  Map<UUID, String> names(Collection<UUID> schoolIds);
}
