package org.teraschool.funds.api;

import java.util.List;
import java.util.UUID;

public record FundsCapabilities(
    boolean canRead,
    boolean canReceipt,
    boolean canAllocate,
    boolean canRefund,
    boolean canPay,
    List<FundsSchoolOption> schools) {
  public FundsCapabilities {
    schools = List.copyOf(schools);
  }

  public record FundsSchoolOption(
      UUID id,
      String name,
      boolean canReceipt,
      boolean canAllocate,
      boolean canRefund,
      boolean canPay) {}
}
