package org.teraschool.funds.api;

import java.util.List;
import java.util.UUID;

public interface FundsQueries {
  FundsCapabilities capabilities();

  StudentBalance balance(UUID studentId);

  List<Receipt> receipts(UUID studentId);

  List<Allocation> allocations(UUID enrollmentId);

  /** 同报名未收金额 = 净应收 − 有效分配。 */
  String outstanding(UUID enrollmentId);

  List<CashRefundView> cashRefunds(UUID enrollmentId);
}
