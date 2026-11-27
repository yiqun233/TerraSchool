package org.teraschool.teaching.api;

import java.math.BigDecimal;
import java.util.UUID;

/** 收入下钻第一层：班级 × 课程的上课确认消费汇总。 */
public record RevenueRow(
    UUID classId,
    String className,
    UUID curriculumId,
    String curriculumName,
    int lessons,
    BigDecimal hours,
    BigDecimal amount) {}
