package org.teraschool.product.api;

import java.util.UUID;

/**
 * 可售产品。
 *
 * <p>{@code publishedPrice} 是当前唯一已发布的价格版本；历史版本保留但不再是可售依据。
 */
public record CourseProduct(
    UUID id,
    UUID schoolId,
    String schoolName,
    UUID curriculumId,
    String curriculumName,
    String name,
    int settlementMode,
    boolean active,
    long version,
    PriceVersion publishedPrice) {}
