package org.teraschool.funds.api;

import java.util.UUID;

/**
 * 确认一笔线下实收。
 *
 * <p>{@code amount} 为十进制字符串；本操作只记录已经发生的收款，不发起银行或支付渠道扣款。
 */
public record CreateReceipt(
    UUID requestId,
    UUID schoolId,
    UUID studentId,
    String amount,
    String method,
    String occurredOn) {}
