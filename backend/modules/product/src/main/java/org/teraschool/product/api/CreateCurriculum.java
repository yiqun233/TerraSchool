package org.teraschool.product.api;

import java.util.UUID;

/** 新建课程；{@code requestId} 是幂等身份，重试返回首次结果。 */
public record CreateCurriculum(
    UUID requestId, UUID schoolId, String name, String category, String subject) {}
