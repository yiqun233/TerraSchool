package org.teraschool.teaching.api;

/** 一个候选课次：能否排、冲突原因码与说明。 */
public record ScheduleSlot(
    String lessonDate, String startsAt, String endsAt, boolean available, String reason) {}
