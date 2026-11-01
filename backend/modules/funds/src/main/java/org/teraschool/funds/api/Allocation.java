package org.teraschool.funds.api;

import java.util.UUID;

public record Allocation(
    UUID id, UUID receiptId, UUID enrollmentId, String amount, String status, String createdAt) {}
