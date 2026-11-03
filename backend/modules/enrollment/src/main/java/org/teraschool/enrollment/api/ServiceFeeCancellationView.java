package org.teraschool.enrollment.api;

import java.util.UUID;

public record ServiceFeeCancellationView(
    UUID enrollmentId,
    String amount,
    String additionalConsume,
    String serviceFeeRefunded,
    String netAmount) {}
