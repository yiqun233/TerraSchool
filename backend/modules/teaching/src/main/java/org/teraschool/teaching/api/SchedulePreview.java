package org.teraschool.teaching.api;

import java.util.List;

/** 排课预览：候选课次、可排数量与被跳过的原因分布。 */
public record SchedulePreview(
    java.util.UUID classId,
    int total,
    int available,
    int skipped,
    String teacherName,
    String roomName,
    List<ScheduleSlot> slots) {
  public SchedulePreview {
    slots = slots == null ? List.of() : List.copyOf(slots);
  }
}
