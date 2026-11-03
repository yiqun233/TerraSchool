package org.teraschool.migration.api;

import java.util.List;

/**
 * 期初导入：一次把一个学校的"历史余额 + 已消耗课时"按学员批量带进新系统。
 *
 * <p>正式导入先保存完整清单，按行提交并记录失败；重试必须使用同一学校、批次、日期和完整原始清单。 每行按学员号定位，金额非零记期初余额，课时大于零记期初消耗。干跑不保存清单。
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
