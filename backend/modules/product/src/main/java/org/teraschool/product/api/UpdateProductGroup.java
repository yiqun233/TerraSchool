package org.teraschool.product.api;

/** 修改产品组。 */
public record UpdateProductGroup(Long expectedVersion, String name, Boolean active) {}
