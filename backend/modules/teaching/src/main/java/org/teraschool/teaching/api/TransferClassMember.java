package org.teraschool.teaching.api;

import java.util.UUID;

/** 把指定报名从一班转到同校同课程的另一班；审批单按报名、动作和 0 元金额校验。 */
public record TransferClassMember(
    UUID requestId, UUID expectedFromClassId, UUID toClassId, UUID approvalId, String reason) {}
