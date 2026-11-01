package org.teraschool.approval.api;

/** 审批决定：{@code APPROVED} 或 {@code REJECTED}，带版本与可选说明。 */
public record DecideApproval(Long expectedVersion, String decision, String note) {}
