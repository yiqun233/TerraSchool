package org.teraschool.enrollment.api;

import java.util.UUID;

/** 经审批的报名应收或服务费减款；金额与影响对象由服务端校验。 */
public record ApplyReduction(
    UUID requestId,
    String action,
    String amount,
    String reason,
    Long expectedVersion,
    UUID approvalId) {}
