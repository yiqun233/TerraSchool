package org.teraschool.funds.api;

import java.util.UUID;

/** 退到账户结果：累计退量/减项、当前净应收、分配与账户余额；重放时返回当前汇总。 */
public record RefundResult(
    UUID enrollmentId,
    String hours,
    String amount,
    String netAmount,
    String allocated,
    String balanceTotal,
    String balanceAvailable) {}
