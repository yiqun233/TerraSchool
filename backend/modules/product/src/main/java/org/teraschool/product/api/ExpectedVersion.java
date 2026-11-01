package org.teraschool.product.api;

/** 只带版本的动作（发布、停用），避免调用方提交无关字段。 */
public record ExpectedVersion(Long expectedVersion) {}
