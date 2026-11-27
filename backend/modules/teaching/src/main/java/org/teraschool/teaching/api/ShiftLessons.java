package org.teraschool.teaching.api;

import java.util.UUID;

/** 平移一个班级在日期段内所有课次的起止时间（分钟）。 */
public record ShiftLessons(UUID requestId, String fromDate, String toDate, int minutes) {}
