package org.teraschool.teaching.api;

/** 学校级时段模板：一天内的分钟区间，课表按它切行。 */
public record MinuteSpace(int beginMinute, int endMinute) {}
