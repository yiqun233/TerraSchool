package org.teraschool.teaching.api;

import java.util.List;

/**
 * 一条链式调课方案。steps 按执行顺序排列：先挪走的课次在前，源头课次在最后一步。
 *
 * @param code 方案编号（方案1、方案2……）
 * @param stepCount 步骤数
 * @param moves 每一步的课次移动
 */
public record SwapSchemePlan(String code, int stepCount, List<Move> moves) {

  /**
   * 一步课次移动。
   *
   * @param lessonId 课次 id
   * @param className 班级名
   * @param teacherName 教师名（可能为空）
   * @param lessonDate 原日期
   * @param startsAt 原开始时间
   * @param endsAt 原结束时间
   * @param newLessonDate 目标日期
   * @param newStartsAt 目标开始时间
   * @param newEndsAt 目标结束时间
   */
  public record Move(
      java.util.UUID lessonId,
      String className,
      String teacherName,
      String lessonDate,
      String startsAt,
      String endsAt,
      String newLessonDate,
      String newStartsAt,
      String newEndsAt) {}
}
