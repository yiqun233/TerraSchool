package org.teraschool.enrollment.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 他域读取报名事实的最小契约（资金分配、教务消费与退款需要）。
 *
 * <p>不做功能授权；调用方必须在自己的用例里完成权限与范围检查，租户隔离由 RLS 承担。价格、课时与结算模式 是报名成立时冻结的快照；{@code cancelledHours}
 * 是已退课时累计。
 */
public interface EnrollmentAccess {
  record EnrollmentRef(
      UUID id,
      UUID schoolId,
      UUID studentId,
      String status,
      BigDecimal netAmount,
      BigDecimal price,
      BigDecimal hours,
      int settlementMode,
      BigDecimal cancelledHours) {}

  EnrollmentRef require(UUID enrollmentId);
}
