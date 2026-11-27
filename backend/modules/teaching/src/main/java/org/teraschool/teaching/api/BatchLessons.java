package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

/**
 * 批量建课次：按日期段 + 周几 + 固定时段生成，遇到停课日或时间冲突的日期跳过并回报原因。
 *
 * @param requestId 幂等键；同一请求重复提交返回同一批课次
 * @param startText 起始日期 yyyy-MM-dd
 * @param endText 结束日期 yyyy-MM-dd
 * @param weekdays 周几，1=周一 … 7=周日
 * @param includeHolidays 是否跳过停课日（false 时遇到停课日跳过；true 时照排）
 */
public record BatchLessons(
    UUID requestId,
    UUID classId,
    String startText,
    String endText,
    List<Integer> weekdays,
    String startsAt,
    String endsAt,
    UUID curriculumId,
    UUID teacherId,
    UUID roomId,
    String topic,
    boolean includeHolidays) {}
