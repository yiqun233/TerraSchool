package org.teraschool.teaching.api;

/** 一个候选课次：能否排、冲突原因码与级别（HARD 阻断 / WARN 仅提示）。 */
public record ScheduleSlot(
    String lessonDate,
    String startsAt,
    String endsAt,
    boolean available,
    String reason,
    String severity) {}
