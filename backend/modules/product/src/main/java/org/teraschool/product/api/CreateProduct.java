package org.teraschool.product.api;

import java.util.UUID;

/** 新建可售产品；课程必须属于同一学校。 */
public record CreateProduct(
    UUID requestId, UUID schoolId, UUID curriculumId, String name, Integer settlementMode) {}
