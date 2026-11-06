package org.teraschool.admissions.api;

import java.util.UUID;

/** 版本化修改学校级客户自动回收规则。 */
public record UpdateCustomerRecyclePolicy(
    UUID requestId,
    long expectedVersion,
    boolean enabled,
    int inactivityDays,
    boolean assignmentAgeEnabled,
    int assignmentAgeDays,
    boolean firstDayNoContactEnabled,
    String firstDayCutoff,
    String reason) {}
