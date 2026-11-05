package org.teraschool.teaching.api;

import java.util.UUID;

/** 已发布且尚无考勤/消费的课次可以在保留变更历史的前提下调整时间。 */
public record RescheduleLesson(
    UUID requestId,
    Long expectedVersion,
    String lessonDate,
    String startsAt,
    String endsAt,
    String reason) {}
