package org.teraschool.teaching.api;

import java.util.UUID;

/** 某个课次时段的可选教师：available=false 时 reason 说明原因（已有课、资源不可用）。 */
public record TeacherOption(
    UUID id,
    String name,
    boolean matchesCurriculum,
    int lessonsInWindow,
    boolean available,
    String reason) {}
