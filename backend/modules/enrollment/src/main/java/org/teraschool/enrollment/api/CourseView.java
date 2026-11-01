package org.teraschool.enrollment.api;

import java.util.UUID;

/** 报名课程明细视图：已退课时与已退费用供模式 4/5 重算单价。 */
public record CourseView(
    UUID curriculumId,
    String curriculumName,
    String hours,
    String amount,
    String cancelledHours,
    String cancelledAmount) {}
