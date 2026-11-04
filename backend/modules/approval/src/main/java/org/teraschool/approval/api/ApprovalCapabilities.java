package org.teraschool.approval.api;

import java.util.List;
import java.util.UUID;

public record ApprovalCapabilities(
    boolean canRead,
    boolean canManage,
    boolean canDecide,
    java.util.UUID currentAccountId,
    List<ApprovalSchoolOption> schools) {
  public ApprovalCapabilities {
    schools = List.copyOf(schools);
  }

  public record ApprovalSchoolOption(UUID id, String name, boolean canManage, boolean canDecide) {}
}
