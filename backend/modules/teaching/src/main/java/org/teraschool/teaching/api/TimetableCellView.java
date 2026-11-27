package org.teraschool.teaching.api;

import java.util.UUID;

/** 教师/学员课表里的一节课。字段与资源日报一致，前端可直接复用卡片渲染。 */
public record TimetableCellView(
    UUID lessonId,
    UUID classId,
    String lessonDate,
    String startsAt,
    String endsAt,
    String className,
    String curriculumName,
    String teacherName,
    String roomName,
    String topic,
    String status) {}
