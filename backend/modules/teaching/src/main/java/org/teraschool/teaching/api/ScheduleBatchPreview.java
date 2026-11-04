package org.teraschool.teaching.api;

import java.util.List;

public record ScheduleBatchPreview(List<SchedulePreview> classes) {
  public ScheduleBatchPreview {
    classes = List.copyOf(classes);
  }
}
