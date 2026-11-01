package org.teraschool.enrollment.api;

import java.util.UUID;

/**
 * 退课时：本次退多少课时、原因与（可选）自定义退费额。
 *
 * <p>旧系统允许操作员为一条报名自定义退费额度，模式 4/5 正是用它重算"退课后实际费用"的单价。 {@code amount} 为空时按单价计算；结算模式 2/3/5 需要指定 {@code
 * curriculumId}（按课程退）。
 */
public record CancelHours(String hours, String reason, UUID curriculumId, String amount) {
  public CancelHours(String hours, String reason) {
    this(hours, reason, null, null);
  }

  public CancelHours(String hours, String reason, UUID curriculumId) {
    this(hours, reason, curriculumId, null);
  }
}
