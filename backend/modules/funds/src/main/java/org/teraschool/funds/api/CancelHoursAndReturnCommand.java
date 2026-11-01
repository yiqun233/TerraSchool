package org.teraschool.funds.api;

import java.util.UUID;

/** 退课时并退到账户：本次退多少课时、原因、幂等身份、（策略要求时）已批准的审批单， 以及结算模式 2/3 需要指定的课程。 */
public record CancelHoursAndReturnCommand(
    UUID requestId, String hours, String reason, UUID approvalId, UUID curriculumId) {
  public CancelHoursAndReturnCommand(UUID requestId, String hours, String reason, UUID approvalId) {
    this(requestId, hours, reason, approvalId, null);
  }
}
