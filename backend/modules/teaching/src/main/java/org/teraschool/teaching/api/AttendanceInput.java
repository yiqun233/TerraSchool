package org.teraschool.teaching.api;

import java.util.UUID;

public record AttendanceInput(UUID enrollmentId, String status) {}
