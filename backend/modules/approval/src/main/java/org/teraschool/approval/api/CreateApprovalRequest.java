package org.teraschool.approval.api;

import java.util.UUID;

/** 申请审批：动作、报名、金额与原因；同一报名同一动作只允许一张待审批单。 */
public record CreateApprovalRequest(
    UUID enrollmentId, String action, String amount, String reason) {}
