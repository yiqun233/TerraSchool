package org.teraschool.teaching.api;

import java.util.List;

/** 确认排课结果：实际创建与跳过的课次数。 */
public record ScheduleResult(int created, int skipped, List<String> skippedDates) {
  public ScheduleResult {
    skippedDates = skippedDates == null ? List.of() : List.copyOf(skippedDates);
  }
}
