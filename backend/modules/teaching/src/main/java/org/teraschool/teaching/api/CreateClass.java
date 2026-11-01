package org.teraschool.teaching.api;

import java.util.UUID;

/** 新建班级；幂等身份 {@code requestId}。 */
public record CreateClass(UUID requestId, UUID schoolId, UUID curriculumId, String name) {}
