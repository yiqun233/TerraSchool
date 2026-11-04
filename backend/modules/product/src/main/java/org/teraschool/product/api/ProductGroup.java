package org.teraschool.product.api;

import java.util.UUID;

/** 学校内产品目录分组。 */
public record ProductGroup(
    UUID id, UUID schoolId, String schoolName, String name, boolean active, long version) {}
