package org.teraschool.enrollment.api;

import java.util.UUID;

/** 按旧项目规则退还报名服务费的指定金额。 */
public record CancelServiceFee(
    String amount, String additionalConsume, String reason, UUID requestId) {
  public CancelServiceFee(String amount, String reason, UUID requestId) {
    this(amount, "0", reason, requestId);
  }
}
