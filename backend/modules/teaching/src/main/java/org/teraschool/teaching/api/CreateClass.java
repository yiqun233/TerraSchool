package org.teraschool.teaching.api;

import java.util.UUID;

/**
 * 新建班级：{@code requestId} 是幂等身份。
 *
 * <p>{@code classType} 为 VIP / LARGE / NORMAL，决定结算模式 0（自动）解析成平均价格(1) 还是课程匹配(2)： 旧系统里 VIP
 * 走平均价格、大班走课程匹配。
 */
public record CreateClass(
    UUID requestId,
    UUID schoolId,
    UUID curriculumId,
    String name,
    String classType,
    boolean groupTraining) {
  public CreateClass(UUID requestId, UUID schoolId, UUID curriculumId, String name) {
    this(requestId, schoolId, curriculumId, name, "NORMAL", false);
  }

  public CreateClass(
      UUID requestId, UUID schoolId, UUID curriculumId, String name, String classType) {
    this(requestId, schoolId, curriculumId, name, classType, false);
  }
}
