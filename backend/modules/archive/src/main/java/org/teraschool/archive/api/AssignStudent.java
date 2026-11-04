package org.teraschool.archive.api;

import java.util.UUID;

/** 学员负责人指定；更换负责人不迁移报名、资金、消费或联系人历史。 */
public record AssignStudent(UUID requestId, Long expectedVersion, UUID ownerAccountId, String reason) {}
