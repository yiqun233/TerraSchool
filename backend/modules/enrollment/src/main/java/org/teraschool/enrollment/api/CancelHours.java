package org.teraschool.enrollment.api;

import java.util.UUID;

/**
 * 退课时：本次退多少课时与原因；金额由服务端按冻结快照计算。
 *
 * <p>结算模式 2/3 需要指定 {@code curriculumId}（按课程退），模式 1 忽略该字段。
 */
public record CancelHours(String hours, String reason, UUID curriculumId) {
  public CancelHours(String hours, String reason) {
    this(hours, reason, null);
  }
}
