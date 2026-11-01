package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

/**
 * 单条报名的课时对账。
 *
 * <p>购买课时 = 已消耗 + 已退 + 剩余；退课时由退款批次写入，消费由关闭课次写入。
 */
public record EnrollmentReconciliation(
    UUID enrollmentId,
    String status,
    String settlementMode,
    String price,
    String hours,
    String consumedHours,
    String consumedAmount,
    String cancelledHours,
    String remainingHours,
    List<ConsumptionView> consumptions) {
  public EnrollmentReconciliation {
    consumptions = List.copyOf(consumptions);
  }
}
