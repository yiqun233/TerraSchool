package org.teraschool.finance.api;

import java.util.List;
import java.util.UUID;

public interface FinancialReportQueries {
  /** 全量累计资金对账，不按财务期间筛选。 */
  FundsReconciliation fundsReconciliation(UUID schoolId);

  /** 按收款发生日、方式和状态汇总的日收款统计；金额为原始收款单金额，不与退款/冲正净额混算。 */
  List<ReceiptDailyTotal> receiptDailyTotals(UUID schoolId, String from, String to);

  /** 业务日运营概况；需要报名、资金和教务三个数据域均可读。 */
  DailyOverview dailyOverview(UUID schoolId, String day);
}
