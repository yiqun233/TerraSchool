package org.teraschool.enrollment.api;

import java.util.UUID;

public interface EnrollmentCommands {
  EnrollmentView create(CreateEnrollment command);

  EnrollmentBatch createBatch(CreateEnrollmentBatch command);

  EnrollmentView approve(UUID id, EnrollmentVersion command);

  /**
   * 退课时：写本次退量并追加应收减项。
   *
   * <p>只校验「不超过购买课时与已退课时」；可退课时还要扣除已消耗课时，由编排的退款用例先用 {@code teaching.ConsumptionAccess} 校验。
   */
  CancellationView cancelHours(UUID id, CancelHours command);

  /** 按服务费剩余额退费并生成应收减项。 */
  ServiceFeeCancellationView cancelServiceFee(UUID id, CancelServiceFee command);

  ServiceFeeCancellationView reverseServiceFeeRefund(UUID id, ReverseServiceFeeRefund command);

  /** 登记一次性减免：写一条应收减项并留审计。 */
  EnrollmentView applyDiscount(UUID id, ApplyDiscount command);
}
