package org.teraschool.admissions.api;

import java.util.UUID;

/** 学校级客户自动回收策略。未配置学校保持关闭。 */
public record CustomerRecyclePolicy(
    UUID schoolId,
    boolean enabled,
    int inactivityDays,
    boolean assignmentAgeEnabled,
    int assignmentAgeDays,
    boolean firstDayNoContactEnabled,
    String firstDayCutoff,
    long version) {}
