package org.teraschool.product.api;

import java.util.UUID;

/** 报名冻结用的价格快照；金额与课时为十进制字符串。 */
public record PriceSnapshot(
    UUID priceVersionId,
    UUID productId,
    UUID schoolId,
    UUID curriculumId,
    String curriculumName,
    String productName,
    String price,
    String hours,
    String serviceFee,
    int settlementMode,
    Integer studyDays,
    String status,
    String effectiveFrom,
    String effectiveTo) {}
