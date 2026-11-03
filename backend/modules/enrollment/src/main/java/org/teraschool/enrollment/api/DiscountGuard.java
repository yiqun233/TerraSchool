package org.teraschool.enrollment.api;

import java.math.BigDecimal;
import java.util.UUID;

/** 各事实所有者在同一租户事务和机构锁内检查减免，避免报名反向依赖资金或教务实现。 */
public interface DiscountGuard {
  void requireAllowed(UUID enrollmentId, BigDecimal newNetAmount);
}
