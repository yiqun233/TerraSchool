package org.teraschool.migration.api;

import java.util.List;

/**
 * 期初对账：按批次对比"旧库应有"与"新系统实际"。
 *
 * <p>逐行成功不等于整批对上：这里直接对比批次里记录的应有值与系统里当前的期初分录/ 期初消耗合计，差额逐行列出，不做任何抹平。
 */
public record OpeningReconciliation(
    String batchId,
    int rows,
    int matched,
    int mismatched,
    String expectedAmount,
    String actualAmount,
    String expectedHours,
    String actualHours,
    List<Row> details) {

  public record Row(
      String studentNo,
      String expectedAmount,
      String expectedHours,
      String actualAmount,
      String actualHours,
      String amountDiff,
      String hoursDiff,
      String status) {}

  public OpeningReconciliation {
    details = details == null ? List.of() : List.copyOf(details);
  }
}
