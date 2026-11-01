package org.teraschool.enrollment.api;

import java.util.UUID;

/** 报名课程明细：该课程的购买课时与费用；合计必须等于价格版本的课时与价格。 */
public record CourseLine(UUID curriculumId, String hours, String amount) {}
