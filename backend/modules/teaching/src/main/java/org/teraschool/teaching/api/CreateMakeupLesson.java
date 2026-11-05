package org.teraschool.teaching.api;

import java.util.UUID;

/** 为原课次已登记缺勤的班级创建关联补课草稿。 */
public record CreateMakeupLesson(
    UUID requestId, String lessonDate, String startsAt, String endsAt, String reason) {}
