package org.teraschool.enrollment.api;

import java.util.UUID;

public record ReverseServiceFeeRefund(UUID requestId, UUID refundId, String reason) {}
