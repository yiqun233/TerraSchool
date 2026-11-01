package org.teraschool.approval.api;

import java.util.UUID;

/** 审批策略：金额低于阈值免审批，阈值 0 表示全部需要审批。 */
public record ApprovalPolicyView(
    UUID id, UUID schoolId, String action, String freeBelow, boolean active, long version) {}
