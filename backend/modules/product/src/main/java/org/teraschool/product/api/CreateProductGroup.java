package org.teraschool.product.api;

import java.util.UUID;

/** 新建产品组。 */
public record CreateProductGroup(UUID requestId, UUID schoolId, String name) {}
