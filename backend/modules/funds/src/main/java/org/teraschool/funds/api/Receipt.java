package org.teraschool.funds.api;

import java.util.UUID;

/** 已确认的收款；金额为十进制字符串，{@code allocatedAmount} 是仍被分配占用的部分。 */
public record Receipt(
    UUID id,
    UUID schoolId,
    UUID studentId,
    String studentName,
    String amount,
    String allocatedAmount,
    String availableAmount,
    String method,
    String occurredOn,
    String status,
    long version) {}
