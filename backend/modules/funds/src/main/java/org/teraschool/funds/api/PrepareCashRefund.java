package org.teraschool.funds.api;

import java.util.UUID;

/** 准备现金退款：冻结账户资金并生成退款单，尚未实际支付。 */
public record PrepareCashRefund(UUID requestId, UUID enrollmentId, String amount) {}
