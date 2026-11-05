package org.teraschool.enrollment.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** 优惠调整的审批闸门，由审批模块在同一租户事务内实现。 */
public interface DiscountApproval {
  void consume(UUID schoolId, UUID enrollmentId, BigDecimal amount, UUID approvalId);

  void consume(String action, UUID schoolId, UUID enrollmentId, BigDecimal amount, UUID approvalId);

  boolean consumeDefer(UUID schoolId, UUID enrollmentId, LocalDate validUntil, UUID approvalId);
}
