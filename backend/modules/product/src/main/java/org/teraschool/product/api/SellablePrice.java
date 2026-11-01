package org.teraschool.product.api;

import java.util.UUID;

/** 指定日期可售的价格快照；报名用它冻结商品与规则，不依赖后续改价。 */
public record SellablePrice(
    UUID productId,
    String productName,
    UUID curriculumId,
    String curriculumName,
    UUID priceVersionId,
    String price,
    String hours,
    String serviceFee,
    int settlementMode,
    Integer studyDays,
    String effectiveFrom,
    String effectiveTo) {}
