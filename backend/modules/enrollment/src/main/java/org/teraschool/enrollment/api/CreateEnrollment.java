package org.teraschool.enrollment.api;

import java.util.List;
import java.util.UUID;

/**
 * 登记报名。
 *
 * <p>只提交学员与已发布的价格版本；单价、课时与商品名称由服务端从价格快照读取，不接受调用方传入总金额。 {@code courses} 是可选的多课程明细（结算模式 2–5
 * 需要）：为空时按价格版本所属课程生成一条明细，其课时与费用等于 价格快照；非空时合计必须与快照一致。
 */
public record CreateEnrollment(
    UUID requestId,
    UUID studentId,
    UUID priceVersionId,
    String enrolledOn,
    List<CourseLine> courses) {
  public CreateEnrollment {
    courses = courses == null ? null : List.copyOf(courses);
  }

  public CreateEnrollment(UUID requestId, UUID studentId, UUID priceVersionId, String enrolledOn) {
    this(requestId, studentId, priceVersionId, enrolledOn, null);
  }
}
