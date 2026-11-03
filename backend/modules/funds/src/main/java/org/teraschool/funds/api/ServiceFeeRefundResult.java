package org.teraschool.funds.api;

import java.util.UUID;

public record ServiceFeeRefundResult(
    UUID enrollmentId,
    UUID refundId,
    String amount,
    String serviceFeeRefunded,
    String netAmount,
    String allocated,
    String balanceTotal,
    String balanceAvailable) {}
