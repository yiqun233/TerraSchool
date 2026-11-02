package org.teraschool.funds.api;

import java.util.UUID;

/**
 * 期初余额：迁移旧库余额时使用，落一条 `OPENING` 账户分录。
 *
 * <p>与收款不同，它不产生"实收"，也不参与分配来源；只用于把历史余额带进新系统，便于对账时单独核对。
 */
public record OpenBalance(
    UUID requestId,
    UUID schoolId,
    UUID studentId,
    String amount,
    String occurredOn,
    String reason) {}
