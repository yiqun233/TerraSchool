package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

public record ScheduleBatchResult(int created, int skipped, List<ClassResult> classes) {
  public ScheduleBatchResult {
    classes = List.copyOf(classes);
  }

  public record ClassResult(UUID classId, ScheduleResult result) {}
}
