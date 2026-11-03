package org.teraschool.enrollment.api;

import java.util.UUID;

/** 登记一次性减免：金额与原因，落到应收上；不做优惠券与促销规则。 */
public record ApplyDiscount(
    UUID requestId, String amount, String reason, Long expectedVersion, UUID approvalId) {
  public ApplyDiscount(UUID requestId, String amount, String reason) {
    this(requestId, amount, reason, null, null);
  }
}
