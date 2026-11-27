package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

/** 批量操作结果：成功的课次、被跳过的课次及原因码。 */
public record BatchLessonOutcome(int done, List<UUID> doneIds, List<Skipped> skipped) {
  public record Skipped(UUID lessonId, String code) {}
}
