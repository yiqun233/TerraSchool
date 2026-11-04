package org.teraschool.finance.api;

import java.util.UUID;

public interface FinancialReportQueries {
  /** 全量累计资金对账，不按财务期间筛选。 */
  FundsReconciliation fundsReconciliation(UUID schoolId);
}
