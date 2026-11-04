package org.teraschool.finance.api;

import java.util.UUID;

/** 学校全部学员的资金总账对账；金额以十进制字符串返回。 */
public record FundsReconciliation(
    UUID schoolId,
    String openingAmount,
    String netReceiptAmount,
    String cashRefundAmount,
    String activeAllocatedAmount,
    String accountBalanceAmount,
    String differenceAmount) {}
