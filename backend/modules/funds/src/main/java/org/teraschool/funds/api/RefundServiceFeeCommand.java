package org.teraschool.funds.api;

import java.util.UUID;

/** 服务费退费：按旧项目退指定金额，审批按学校现行退课时退款策略执行。 */
public record RefundServiceFeeCommand(
    UUID requestId, String amount, String additionalConsume, String reason, UUID approvalId) {
  public RefundServiceFeeCommand(UUID requestId, String amount, String reason, UUID approvalId) {
    this(requestId, amount, "0", reason, approvalId);
  }
}
