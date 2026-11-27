package org.teraschool.teaching.api;

import java.math.BigDecimal;
import java.util.UUID;

/** 班级开设的课程：多课程班按课程分别记课时与价格，primary 表示班级主课程。 */
public record ClassCurriculumView(
    UUID curriculumId,
    String curriculumName,
    BigDecimal hours,
    BigDecimal price,
    boolean primary) {}
