package org.teraschool.teaching.api;

/** 拖拽可行性查询：目标日期范围（含首尾），服务端按学校时段一次算整屏。 */
public record MoveSchemeQuery(String startDate, String endDate) {}
