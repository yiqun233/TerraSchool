package org.teraschool.product.api;

/** 修改课程；整套提交并带读取版本。 */
public record UpdateCurriculum(
    Long expectedVersion, String name, String category, String subject, Boolean active) {}
