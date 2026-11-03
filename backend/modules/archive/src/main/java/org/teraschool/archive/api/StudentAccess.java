package org.teraschool.archive.api;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 他域读取学员身份的最小契约。
 *
 * <p>只解决“这个学员是否存在、属于哪个学校”，不做功能授权：调用方必须先完成自己的权限与数据范围检查， 本契约依赖连接上的租户 RLS 保证不跨租户读取。
 */
public interface StudentAccess {
  record StudentRef(UUID id, UUID schoolId, String studentNo, String name) {}

  StudentRef require(UUID studentId);

  Map<UUID, String> names(Collection<UUID> studentIds);

  /** 调用方须在当前租户事务中验证学校档案读取权限；仅返回已存在的学员号。 */
  Set<String> existingNumbers(UUID schoolId, Collection<String> studentNumbers);
}
