package org.teraschool.archive.api;

import java.util.List;
import java.util.UUID;

/** 重复档案提示：只提示核验，不自动覆盖、合并或改写已有主档。 */
public record DuplicateHint(UUID studentId, String studentNo, String name, String reason) {
  public static final String SAME_NAME_AND_BIRTHDAY = "SAME_NAME_AND_BIRTHDAY";
  public static final String SAME_NAME_AND_PHONE = "SAME_NAME_AND_PHONE";
  public static final List<String> REASONS = List.of(SAME_NAME_AND_BIRTHDAY, SAME_NAME_AND_PHONE);
}
