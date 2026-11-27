package org.teraschool.teaching.api;

import java.math.BigDecimal;
import java.util.UUID;

/** 收入下钻第二层：某班某课程下，逐课次 × 学员的消费明细。 */
public record RevenueDetail(
    UUID lessonId,
    String lessonDate,
    String className,
    String studentName,
    String curriculumName,
    BigDecimal hours,
    BigDecimal amount) {}
