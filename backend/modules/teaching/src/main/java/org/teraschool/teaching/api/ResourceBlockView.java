package org.teraschool.teaching.api;

import java.util.UUID;

/** 资源不可用时间视图：教师与教室各给出 id 与名称，便于列表展示。 */
public record ResourceBlockView(
    UUID id,
    UUID teacherId,
    String teacherName,
    UUID roomId,
    String roomName,
    String blockDate,
    String startsAt,
    String endsAt,
    String reason) {}
