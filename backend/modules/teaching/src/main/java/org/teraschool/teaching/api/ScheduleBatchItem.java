package org.teraschool.teaching.api;

import java.util.UUID;

/** 一个班级与其固定周次规则。 */
public record ScheduleBatchItem(UUID classId, ScheduleRule rule) {}
