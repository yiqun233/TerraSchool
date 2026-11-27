package org.teraschool.teaching.api;

import java.util.UUID;

/** 某个课次时段的可选教室；容量不足时仍返回，由前端提示。 */
public record RoomOption(
    UUID id,
    String name,
    String campusName,
    Integer capacity,
    int lessonsInWindow,
    boolean available,
    String reason) {}
