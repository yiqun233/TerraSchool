package org.teraschool.product.api;

import java.util.UUID;

/** 他域读取课程身份的最小契约；授权由调用方负责，租户隔离由 RLS 承担。 */
public interface CurriculumAccess {
  record CurriculumRef(UUID id, UUID schoolId, String name, boolean active) {}

  CurriculumRef require(UUID curriculumId);
}
