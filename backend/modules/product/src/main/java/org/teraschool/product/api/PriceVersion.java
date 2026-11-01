package org.teraschool.product.api;

import java.util.UUID;

/**
 * 价格与课时版本。
 *
 * <p>金额与课时以十进制字符串传输，服务端用 BigDecimal 校验与保存；发布后不可改写，改价需要新版本。 单位：金额为元（两位小数），课时为课时（两位小数）。
 */
public record PriceVersion(
    UUID id,
    UUID productId,
    int versionNo,
    String price,
    String hours,
    String serviceFee,
    int settlementMode,
    Integer studyDays,
    String effectiveFrom,
    String effectiveTo,
    String status,
    long version) {}
