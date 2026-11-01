package org.teraschool.teaching.api;

import java.util.UUID;

/** 资源不可用时间：教师活动/休假或教室预订；teacherId 与 roomId 二选一。 */
public record ResourceBlock(
    UUID schoolId,
    UUID teacherId,
    UUID roomId,
    String blockDate,
    String startsAt,
    String endsAt,
    String reason) {}
