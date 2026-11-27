package org.teraschool.teaching.api;

import java.util.UUID;

/** 课次资源设置结果：课程、教师、教室的快照与最新版本。 */
public record LessonResourceView(
    UUID lessonId,
    UUID curriculumId,
    String curriculumName,
    UUID teacherId,
    String teacherName,
    UUID roomId,
    String roomName,
    long version) {}
