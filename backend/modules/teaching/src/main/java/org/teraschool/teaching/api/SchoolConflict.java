package org.teraschool.teaching.api;

import java.util.UUID;

/** 全校冲突中心的一行：哪节课、和哪节课、什么类型的冲突。 */
public record SchoolConflict(
    UUID lessonId,
    String className,
    String lessonDate,
    String startsAt,
    String endsAt,
    String teacherName,
    String roomName,
    String type,
    String message,
    UUID otherLessonId,
    String otherClassName,
    String otherLessonDate,
    String otherStartsAt) {}
