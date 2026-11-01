package org.teraschool.approval.api;

import java.util.UUID;

/** 维护某学校某动作的审批策略；{@code expectedVersion} 为空表示新建。 */
public record UpsertPolicy(
    UUID schoolId, String action, String freeBelow, Boolean active, Long expectedVersion) {}
