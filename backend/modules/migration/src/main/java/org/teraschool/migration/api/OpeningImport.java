package org.teraschool.migration.api;

import java.util.List;

/**
 * 期初导入：一次把一个学校的"历史余额 + 已消耗课时"按学员批量带进新系统。
 *
 * <p>每行按学员号定位：金额非零记一条期初余额，课时大于零记一条期初消耗； 逐行返回结果，单行失败不影响其他行。同一批次重跑幂等（按 batchId + 学员号派生请求号）。
 */
public record OpeningImport(
    String batchId, String occurredOn, String reason, List<Row> rows, boolean dryRun) {

  public record Row(String studentNo, String amount, String hours, String consumedAmount) {}

  public record RowResult(String studentNo, String status, String detail) {}

  public record Report(
      String batchId,
      String mode,
      int ok,
      int skipped,
      int failed,
      String totalAmount,
      String totalHours,
      List<RowResult> rows) {
    public Report {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }
}
