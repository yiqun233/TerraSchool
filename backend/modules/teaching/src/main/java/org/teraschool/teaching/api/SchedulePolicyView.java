package org.teraschool.teaching.api;

import java.util.UUID;

public record SchedulePolicyView(
    UUID schoolId,
    boolean blockTeacherUnavailable,
    boolean blockRoomUnavailable,
    int minGapMinutes,
    long version) {}
