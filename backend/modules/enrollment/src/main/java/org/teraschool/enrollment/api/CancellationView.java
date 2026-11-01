package org.teraschool.enrollment.api;

import java.util.UUID;

/** 退课时结果：本次退量、费用减项、退后的净应收与累计已退课时。 */
public record CancellationView(
    UUID id,
    UUID enrollmentId,
    String hours,
    String amount,
    String netAmount,
    String cancelledHours) {}
