package org.teraschool.funds.api;

import java.util.UUID;

/** 退到账户结果：本次退量/减项、调整后的分配与账户余额。 */
public record RefundResult(
    UUID enrollmentId,
    String hours,
    String amount,
    String netAmount,
    String allocated,
    String balanceTotal,
    String balanceAvailable) {}
