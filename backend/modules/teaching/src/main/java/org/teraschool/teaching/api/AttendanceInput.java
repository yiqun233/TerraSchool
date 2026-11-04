package org.teraschool.teaching.api;

import java.util.UUID;

public record AttendanceInput(UUID enrollmentId, String status, Integer attendedMinutes) {
  public AttendanceInput(UUID enrollmentId, String status) {
    this(enrollmentId, status, null);
  }
}
