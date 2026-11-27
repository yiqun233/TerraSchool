package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

/** 批量录考勤：只落考勤，不确认课次；确认仍走 ConfirmAttendance。 */
public record SaveAttendance(UUID requestId, List<AttendanceInput> records) {}
