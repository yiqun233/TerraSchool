package org.teraschool.teaching.api;

import java.util.UUID;

/** 退班仅结束指定班级关系；费用/课时结算另走已定义的资金业务。 */
public record WithdrawClassMember(UUID requestId, UUID classId, UUID approvalId, String reason) {}
