package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

/**
 * 授课确认与考勤登记；必须覆盖当前全部在班报名。
 *
 * <p>{@code actualTeacherId}/{@code actualRoomId} 记录“实际上课”的教师与教室，为空时沿用排课值。
 * 排课值仍用于冲突检测与课表展示，实际值用于课时结算与教师业绩口径。
 */
public record ConfirmAttendance(
    Long expectedVersion, List<AttendanceInput> records, UUID actualTeacherId, UUID actualRoomId) {

  public ConfirmAttendance(Long expectedVersion, List<AttendanceInput> records) {
    this(expectedVersion, records, null, null);
  }

  public ConfirmAttendance {
    records = records == null ? List.of() : List.copyOf(records);
  }
}
