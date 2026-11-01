package org.teraschool.teaching.api;

import java.util.UUID;

/**
 * 课次视图。
 *
 * <p>日期与起止时间分开表达，{@code hours} 是本次授课课时（SC01 为 1.00），与钟表分钟数不是同一件事。
 */
public record LessonView(
    UUID id,
    UUID classId,
    String lessonDate,
    String startsAt,
    String endsAt,
    String teacherName,
    String roomName,
    String topic,
    String hours,
    String status,
    long version,
    int attendanceCount) {}
