package org.teraschool.product.api;

import java.util.UUID;

/** 将产品归入/移出本校产品组；groupId 为空表示移出分组。 */
public record ChangeProductGroup(Long expectedVersion, UUID groupId) {}
