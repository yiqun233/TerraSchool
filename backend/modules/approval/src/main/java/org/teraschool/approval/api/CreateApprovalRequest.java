package org.teraschool.approval.api;

import java.time.LocalDate;
import java.util.UUID;

/** 申请审批：请求标识、动作、报名、金额与原因；同一报名同一动作只允许一张待审批单。 */
public record CreateApprovalRequest(
    UUID requestId,
    UUID enrollmentId,
    String action,
    String amount,
    String reason,
    LocalDate requestedUntil) {
  public CreateApprovalRequest(
      UUID requestId, UUID enrollmentId, String action, String amount, String reason) {
    this(requestId, enrollmentId, action, amount, reason, null);
  }
}
