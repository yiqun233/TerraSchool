package org.teraschool.funds.api;

import java.util.List;
import java.util.UUID;

public interface FundsQueries {
  FundsCapabilities capabilities();

  StudentBalance balance(UUID studentId);

  List<Receipt> receipts(UUID studentId);

  List<Allocation> allocations(UUID enrollmentId);

  /** 收款 CSV 导出（学校 + 日期范围），导出写审计。 */
  String exportReceiptsCsv(UUID schoolId, String from, String to);

  /** 同报名未收金额 = 净应收 − 有效分配。 */
  String outstanding(UUID enrollmentId);

  List<CashRefundView> cashRefunds(UUID enrollmentId);
}
