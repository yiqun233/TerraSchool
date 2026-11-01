package org.teraschool.migration.api;

import java.util.List;
import java.util.UUID;

/**
 * 迁移报告：干跑给"将要做什么"，确认给"实际做了什么"。
 *
 * <p>{@code studentsStaged} 等是暂存区计数，{@code imported} 是已导入台账计数； {@code issues}
 * 是需要人工处理的差异（缺学员、金额不一致、重复学员号等）。
 */
public record MigrationReport(
    UUID schoolId,
    String mode,
    int studentsStaged,
    int studentsImported,
    int studentsPending,
    String stagedReceiptTotal,
    String importedReceiptTotal,
    List<MigrationIssue> issues) {
  public MigrationReport {
    issues = issues == null ? List.of() : List.copyOf(issues);
  }
}
