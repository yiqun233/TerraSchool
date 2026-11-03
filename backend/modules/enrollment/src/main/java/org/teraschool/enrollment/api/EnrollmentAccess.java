package org.teraschool.enrollment.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 他域读取报名事实的最小契约（资金分配、教务消费与退款需要）。
 *
 * <p>不做功能授权；调用方必须在自己的用例里完成权限与范围检查，租户隔离由 RLS 承担。价格、课时与结算模式 是报名成立时冻结的快照；{@code cancelledHours}
 * 是已退课时累计；{@code courses} 是课程明细，供结算模式 2–5 计价与封顶。
 */
public interface EnrollmentAccess {
  record CourseRef(
      UUID curriculumId,
      BigDecimal hours,
      BigDecimal amount,
      BigDecimal cancelledHours,
      BigDecimal cancelledAmount) {}

  record EnrollmentRef(
      UUID id,
      UUID schoolId,
      UUID studentId,
      String status,
      BigDecimal netAmount,
      BigDecimal price,
      BigDecimal serviceFee,
      BigDecimal discount,
      BigDecimal hours,
      int settlementMode,
      BigDecimal cancelledHours,
      List<CourseRef> courses) {
    public EnrollmentRef {
      courses = courses == null ? List.of() : List.copyOf(courses);
    }
  }

  EnrollmentRef require(UUID enrollmentId);

  /**
   * 把声明为 0（自动）的结算模式解析成 1/2 并持久化。
   *
   * <p>解析只发生一次：首个金额事实（进班/消费/退费）确定班型后写入，之后消费与退费共用同一口径。
   */
  void resolveSettlementMode(UUID enrollmentId, int resolvedMode);
}
