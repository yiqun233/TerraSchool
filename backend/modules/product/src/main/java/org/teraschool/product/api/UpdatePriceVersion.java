package org.teraschool.product.api;

/** 修改仍为草稿的价格版本；已发布版本不可修改。 */
public record UpdatePriceVersion(
    Long expectedVersion,
    String price,
    String hours,
    String serviceFee,
    Integer settlementMode,
    Integer studyDays,
    String effectiveFrom,
    String effectiveTo) {}
