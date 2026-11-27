package org.teraschool.teaching.api;

import java.util.UUID;

/** 把某一周的课表复制到下一周（+7 天），新生成的课次为草稿。 */
public record CopyWeek(UUID requestId, String sourceWeekStart) {}
