package org.teraschool.archive.api;

/**
 * 学员列表的关键计数：总数、在读、停用、本月新增。
 *
 * <p>只统计未合并档案，与列表使用同一组学校范围与检索条件；跨模块的“课时不足”、 “待续费”不在档案模块计算，避免页面用假数据。
 */
public record StudentCounts(long total, long active, long inactive, long thisMonth) {}
