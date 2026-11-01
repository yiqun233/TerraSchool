package org.teraschool.enrollment.api;

import java.util.UUID;

public interface EnrollmentCommands {
  EnrollmentView create(CreateEnrollment command);

  EnrollmentView approve(UUID id, EnrollmentVersion command);

  /**
   * 退课时：写本次退量并追加应收减项。
   *
   * <p>只校验「不超过购买课时与已退课时」；可退课时还要扣除已消耗课时，由编排的退款用例先用 {@code teaching.ConsumptionAccess} 校验。
   */
  CancellationView cancelHours(UUID id, CancelHours command);
}
