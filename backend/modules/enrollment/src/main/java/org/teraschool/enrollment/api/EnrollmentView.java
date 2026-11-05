package org.teraschool.enrollment.api;

import java.util.List;
import java.util.UUID;

/**
 * 报名视图：商品、价格与课时是报名成立时冻结的快照。
 *
 * <p>{@code netAmount} 为当前净应收（原始约定 + 调整），金额与课时为十进制字符串；{@code courses} 为课程明细。
 */
public record EnrollmentView(
    UUID id,
    UUID schoolId,
    UUID studentId,
    String studentName,
    UUID productId,
    UUID priceVersionId,
    String curriculumName,
    String productName,
    String price,
    String hours,
    String serviceFee,
    int settlementMode,
    String status,
    String netAmount,
    long version,
    String createdAt,
    String validUntil,
    List<CourseView> courses) {
  public EnrollmentView {
    courses = courses == null ? List.of() : List.copyOf(courses);
  }
}
