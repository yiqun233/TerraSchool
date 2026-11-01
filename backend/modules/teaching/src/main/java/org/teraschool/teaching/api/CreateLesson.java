package org.teraschool.teaching.api;

import java.util.UUID;

/**
 * 起草课次；草稿不占用资源，也不产生消费。
 *
 * <p>教师与教室可以按实体（{@code teacherId}/{@code roomId}）指定，也可以只给名称；给实体时名称由服务端回填快照。
 */
public record CreateLesson(
    UUID requestId,
    UUID classId,
    String lessonDate,
    String startsAt,
    String endsAt,
    String teacherName,
    String roomName,
    String topic,
    String hours,
    UUID teacherId,
    UUID roomId) {
  public CreateLesson(
      UUID requestId,
      UUID classId,
      String lessonDate,
      String startsAt,
      String endsAt,
      String teacherName,
      String roomName,
      String topic,
      String hours) {
    this(
        requestId,
        classId,
        lessonDate,
        startsAt,
        endsAt,
        teacherName,
        roomName,
        topic,
        hours,
        null,
        null);
  }
}
