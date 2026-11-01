package org.teraschool.migration.api;

/** 对账问题：类型 + 关联键 + 说明。 */
public record MigrationIssue(String kind, String key, String detail) {}
