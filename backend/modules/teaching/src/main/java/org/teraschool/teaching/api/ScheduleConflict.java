package org.teraschool.teaching.api;

/** 排课稳定错误码，例如 SCHEDULE_RANGE_TOO_LONG、LESSON_OVERLAP。 */
public class ScheduleConflict extends RuntimeException {
  private final String code;

  public ScheduleConflict(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
