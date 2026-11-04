package org.teraschool.teaching.api;

import java.util.UUID;

/**
 * 自动排课规则：在 [startDate, endDate] 内按周几与固定时段生成课次。
 *
 * <p>只表达「每周几、几点到几点、多少课时」；教师与教室可按实体指定（{@code teacherId}/{@code roomId}），
 * 也可以只给名称。给实体时以实体的名称作为快照，并按实体做冲突检测。
 */
public record ScheduleRule(
    String startDate,
    String endDate,
    java.util.List<Integer> weekdays,
    String startsAt,
    String endsAt,
    String hours,
    String teacherName,
    String roomName,
    String topic,
    UUID teacherId,
    UUID roomId,
    boolean autoAssignResources) {
  public ScheduleRule {
    weekdays = weekdays == null ? java.util.List.of() : java.util.List.copyOf(weekdays);
  }

  public ScheduleRule(
      String startDate,
      String endDate,
      java.util.List<Integer> weekdays,
      String startsAt,
      String endsAt,
      String hours,
      String teacherName,
      String roomName,
      String topic) {
    this(
        startDate,
        endDate,
        weekdays,
        startsAt,
        endsAt,
        hours,
        teacherName,
        roomName,
        topic,
        null,
        null,
        false);
  }

  public ScheduleRule(
      String startDate,
      String endDate,
      java.util.List<Integer> weekdays,
      String startsAt,
      String endsAt,
      String hours,
      String teacherName,
      String roomName,
      String topic,
      UUID teacherId,
      UUID roomId) {
    this(
        startDate,
        endDate,
        weekdays,
        startsAt,
        endsAt,
        hours,
        teacherName,
        roomName,
        topic,
        teacherId,
        roomId,
        false);
  }
}
