package org.teraschool.teaching.api;

import java.util.UUID;

/**
 * 新建班级：{@code requestId} 是幂等身份。
 *
 * <p>{@code classType} 为 VIP / LARGE / NORMAL / ONE_ON_ONE / ONE_TO_FOUR / ONE_TO_EIGHT，决定结算模式
 * 0（自动）解析成平均价格(1) 还是课程匹配(2)：旧系统里 VIP 与一对一走平均价格、大班走课程匹配。一对四 / 一对八 / 一对一会带出默认名额（4 / 8 / 1），显式传入的
 * {@code capacity} 优先。
 *
 * <p>{@code campusId} 是班级上课校区；报名校区必须与之一致才能进班（旧系统“在哪个校区报名就在哪个校区上课”）。
 */
public record CreateClass(
    UUID requestId,
    UUID schoolId,
    UUID curriculumId,
    String name,
    String classType,
    boolean groupTraining,
    Integer capacity,
    UUID campusId) {
  public CreateClass(UUID requestId, UUID schoolId, UUID curriculumId, String name) {
    this(requestId, schoolId, curriculumId, name, "NORMAL", false, null, null);
  }

  public CreateClass(
      UUID requestId, UUID schoolId, UUID curriculumId, String name, String classType) {
    this(requestId, schoolId, curriculumId, name, classType, false, null, null);
  }

  public CreateClass(
      UUID requestId,
      UUID schoolId,
      UUID curriculumId,
      String name,
      String classType,
      boolean groupTraining) {
    this(requestId, schoolId, curriculumId, name, classType, groupTraining, null, null);
  }

  public CreateClass(
      UUID requestId,
      UUID schoolId,
      UUID curriculumId,
      String name,
      String classType,
      boolean groupTraining,
      Integer capacity) {
    this(requestId, schoolId, curriculumId, name, classType, groupTraining, capacity, null);
  }
}
