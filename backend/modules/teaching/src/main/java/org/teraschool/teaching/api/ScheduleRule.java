package org.teraschool.teaching.api;

/**
 * 自动排课规则：在 [startDate, endDate] 内按周几与固定时段生成课次。
 *
 * <p>只表达"每周几、几点到几点、多少课时"，不做多课程/多教师组合；教师与教室用于冲突检测。
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
    String topic) {
  public ScheduleRule {
    weekdays = weekdays == null ? java.util.List.of() : java.util.List.copyOf(weekdays);
  }
}
