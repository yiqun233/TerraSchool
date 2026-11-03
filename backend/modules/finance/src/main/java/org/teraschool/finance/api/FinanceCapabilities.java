package org.teraschool.finance.api;

import java.util.List;

public record FinanceCapabilities(
    boolean canRead, boolean canLock, boolean canUnlock, List<FinanceSchoolOption> schools) {
  public FinanceCapabilities {
    schools = List.copyOf(schools);
  }
}
