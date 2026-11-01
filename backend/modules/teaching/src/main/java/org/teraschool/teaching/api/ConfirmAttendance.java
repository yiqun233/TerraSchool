package org.teraschool.teaching.api;

import java.util.List;

/** 授课确认与考勤登记；必须覆盖当前全部在班报名。 */
public record ConfirmAttendance(Long expectedVersion, List<AttendanceInput> records) {
  public ConfirmAttendance {
    records = records == null ? List.of() : List.copyOf(records);
  }
}
