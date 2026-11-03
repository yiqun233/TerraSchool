package org.teraschool.funds.api;

import java.util.UUID;

public interface FundsCommands {
  Receipt createReceipt(CreateReceipt command);

  Receipt voidReceipt(UUID id, FundsVersion command);

  Allocation allocate(AllocateReceipt command);

  /** 记一条期初余额（迁移用）；不产生实收，也不影响未收金额。 */
  StudentBalance openBalance(OpenBalance command);

  /** 释放分配只把状态从 ACTIVE 迁到 RELEASED，不需要额外版本。 */
  Allocation releaseAllocation(UUID id);

  /** 退课时并退到账户：需要 REFUND_APPLY。 */
  RefundResult cancelHoursAndReturnToAccount(
      UUID enrollmentId, CancelHoursAndReturnCommand command);

  /** 服务费独立退费并重排报名分配，差额留在学员账户。 */
  ServiceFeeRefundResult refundServiceFee(UUID enrollmentId, RefundServiceFeeCommand command);

  /** 冲正尚未锁月的服务费退费，写补偿记录并重新分配余额。 */
  ServiceFeeRefundResult reverseServiceFeeRefund(
      UUID enrollmentId, ReverseServiceFeeRefundCommand command);

  /** 准备现金退款并冻结资金：需要 REFUND_PAY。 */
  CashRefundView prepareCashRefund(PrepareCashRefund command);

  /** 确认已实际支付：核销冻结并记现金退出。 */
  CashRefundView confirmCashRefundPaid(UUID id, ConfirmCashRefund command);

  /** 取消尚未支付的退款单并释放冻结。 */
  CashRefundView cancelCashRefund(UUID id, FundsVersion command);
}
