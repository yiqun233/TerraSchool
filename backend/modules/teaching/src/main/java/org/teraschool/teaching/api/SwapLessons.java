package org.teraschool.teaching.api;

import java.util.UUID;

/** 同班两个无考勤/消费已发布课次交换日期与时段。 */
public record SwapLessons(
    UUID requestId,
    UUID otherLessonId,
    Long expectedVersion,
    Long expectedOtherVersion,
    String reason) {}
