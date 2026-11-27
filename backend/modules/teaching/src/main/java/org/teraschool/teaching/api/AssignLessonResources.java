package org.teraschool.teaching.api;

import java.util.UUID;

/** 设置课次的课程、教师与教室；字段为 null 表示清空该项。 */
public record AssignLessonResources(
    UUID requestId, Long expectedVersion, UUID teacherId, UUID roomId, UUID curriculumId) {}
