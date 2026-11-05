package org.teraschool.archive.api;

import java.util.UUID;

/** 域内学员关联检查；实现方只查询自己拥有的业务事实，并在学员合并事务中调用。 */
public interface StudentMergeGuard {
  /** 稳定的无敏感信息阻止原因码，例如 ENROLLMENT_HISTORY。 */
  String blocker(UUID studentId);
}
