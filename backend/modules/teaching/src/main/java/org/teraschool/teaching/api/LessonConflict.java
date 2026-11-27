package org.teraschool.teaching.api;

import java.util.UUID;

/**
 * 课次冲突：类型取自旧系统 ClashTypeDictionary 的三组语义（班级/教师/教室/学员/停课）。
 *
 * @param type
 *     冲突类型：CLASS_OVERLAP、TEACHER_CLASH、ROOM_CLASH、STUDENT_CLASH、SCHOOL_CLOSED、TEACHER_UNAVAILABLE、ROOM_UNAVAILABLE
 * @param message 给操作人看的中文说明
 * @param otherLessonId 造成冲突的另一节课（停课类为 null）
 * @param otherClassName 另一节课所属班级
 * @param otherLessonDate 另一节课日期
 * @param otherStartsAt 另一节课开始时间
 */
public record LessonConflict(
    String type,
    String message,
    UUID otherLessonId,
    String otherClassName,
    String otherLessonDate,
    String otherStartsAt) {}
