package org.teraschool.teaching.api;

/**
 * 调课方案搜索的日期范围，最多 31 天。
 *
 * @param startDate 搜索起始日期（含）
 * @param endDate 搜索结束日期（含）
 */
public record SwapSchemeQuery(String startDate, String endDate) {}
