package org.teraschool.identity.api;

import java.util.UUID;
import java.util.function.Function;

/** 组织模块拥有学校写入；身份模块在同一机构锁和事务中维护初始化授权。 */
public interface SchoolProvisioning {
  record Result(UUID id, String name, long version, boolean created) {}

  boolean canCreateSchool();

  Result provision(Function<Actor, Result> createSchool);

  /** 全学校管理员持授权锁执行学校维护。 */
  <T> T manage(Function<Actor, T> work);
}
