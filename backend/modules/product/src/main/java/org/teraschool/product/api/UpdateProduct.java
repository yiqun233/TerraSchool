package org.teraschool.product.api;

/** 修改产品；结算模式默认值变化不影响已发布的价格版本。 */
public record UpdateProduct(
    Long expectedVersion, String name, Integer settlementMode, Boolean active) {}
