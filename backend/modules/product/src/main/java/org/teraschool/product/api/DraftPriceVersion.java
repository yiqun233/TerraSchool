package org.teraschool.product.api;

import java.util.UUID;

/**
 * 起草价格版本。
 *
 * <p>金额、课时、服务费都是十进制字符串；{@code effectiveFrom}/{@code effectiveTo} 为业务日期（YYYY-MM-DD）。
 */
public record DraftPriceVersion(
    UUID requestId,
    UUID productId,
    String price,
    String hours,
    String serviceFee,
    Integer settlementMode,
    Integer studyDays,
    String effectiveFrom,
    String effectiveTo) {}
