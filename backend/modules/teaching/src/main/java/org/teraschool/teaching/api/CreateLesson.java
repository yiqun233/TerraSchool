package org.teraschool.teaching.api;

import java.util.UUID;

/** 起草课次；草稿不占用资源，也不产生消费。 */
public record CreateLesson(
    UUID requestId,
    UUID classId,
    String lessonDate,
    String startsAt,
    String endsAt,
    String teacherName,
    String roomName,
    String topic,
    String hours) {}
