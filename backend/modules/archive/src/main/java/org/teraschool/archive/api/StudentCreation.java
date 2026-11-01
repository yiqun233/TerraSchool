package org.teraschool.archive.api;

import java.util.List;
import java.util.UUID;

/**
 * 新建结果。
 *
 * <p>同时返回可能重复的档案与被复用的联系人，让调用方在页面提示人工核验，而不是悄悄合并。
 */
public record StudentCreation(
    StudentProfile profile, List<DuplicateHint> possibleDuplicates, List<UUID> reusedContactIds) {
  public StudentCreation {
    possibleDuplicates = List.copyOf(possibleDuplicates);
    reusedContactIds = List.copyOf(reusedContactIds);
  }
}
