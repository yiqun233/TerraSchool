package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

public record TeachingCapabilities(
    boolean canRead,
    boolean canMaintain,
    boolean canRecordAttendance,
    boolean canCloseLesson,
    List<SchoolOption> schools) {
  public TeachingCapabilities {
    schools = List.copyOf(schools);
  }

  public record SchoolOption(
      UUID id,
      String name,
      boolean maintainable,
      boolean canRecordAttendance,
      boolean canCloseLesson) {}
}
