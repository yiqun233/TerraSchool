package org.teraschool.funds.api;

import java.util.UUID;

/** 现金退款单：准备（冻结）/已付/已取消；金额为十进制字符串。 */
public record CashRefundView(
    UUID id,
    UUID enrollmentId,
    UUID studentId,
    String amount,
    String status,
    String reference,
    String preparedAt,
    String paidAt,
    long version) {}
