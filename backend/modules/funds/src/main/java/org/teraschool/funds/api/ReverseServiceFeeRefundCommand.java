package org.teraschool.funds.api;

import java.util.UUID;

public record ReverseServiceFeeRefundCommand(
    UUID requestId, UUID refundId, String reason, UUID approvalId) {}
