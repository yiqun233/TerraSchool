package org.teraschool.approval.api;

import java.time.LocalDate;
import java.util.UUID;

/** 审批单：金额为十进制字符串；{@code usedAt} 非空表示已被业务动作消耗。 */
public record ApprovalRequestView(
    UUID id,
    UUID schoolId,
    String action,
    UUID enrollmentId,
    String amount,
    String reason,
    String status,
    UUID requestedBy,
    UUID decidedBy,
    String decisionNote,
    String usedAt,
    long version,
    String createdAt,
    LocalDate requestedUntil) {}
