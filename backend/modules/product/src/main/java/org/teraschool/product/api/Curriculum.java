package org.teraschool.product.api;

import java.util.UUID;

/** 课程主数据；归属学校，名称在同一学校内唯一。 */
public record Curriculum(
    UUID id,
    UUID schoolId,
    String schoolName,
    String name,
    String category,
    String subject,
    boolean active,
    long version) {}
