package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

public interface TeachingCommands {
  ClassView createClass(CreateClass command);

  /** 按一对一报名自动开班（容量 1、班型 ONE_ON_ONE）并把该报名进班。 */
  ClassView createOneOnOneClass(UUID enrollmentId, OneOnOneClass command);

  /** 修改班级容量；不得低于当前在班人数，null 表示不限。 */
  ClassView updateClassCapacity(UUID classId, UpdateClassCapacity command);

  /** 结班并关闭其报名在班关系；只允许没有未结课次的班级。 */
  ClassView closeClass(UUID classId, LessonVersion command);

  /** 取消没有课次和有效报名关系的空班；保留历史记录并要求原因与版本。 */
  ClassView cancelClass(UUID classId, CancelClass command);

  ClassView addMember(UUID classId, AddClassMember command);

  /** 原子将一条报名加入多个匹配课程班级，任一不满足时全部回滚。 */
  List<ClassView> addMembers(AddClassMembers command);

  /** 经审批/免批策略后在同校同课程班级间转班；报名金额与课时留在原报名上。 */
  ClassView transferMember(UUID enrollmentId, TransferClassMember command);

  /** 经 ENROLLMENT_WITHDRAWAL 审批后结束班级成员关系，保留原报名资金/课时事实。 */
  ClassView withdrawMember(UUID enrollmentId, WithdrawClassMember command);

  LessonView createLesson(CreateLesson command);

  LessonView publishLesson(UUID id, LessonVersion command);

  /** 批量发布：逐个处理，非草稿或校验失败的课次跳过并返回原因码。 */
  BatchLessonOutcome publishLessons(BatchLessonIds command);

  /** 批量关课：逐个处理，非已确认、缺考勤或超课时的课次跳过并返回原因码。 */
  BatchLessonOutcome closeLessons(BatchLessonIds command);

  /** 调整尚未考勤/消费的已发布课次，原时间写入变更历史。 */
  LessonView rescheduleLesson(UUID id, RescheduleLesson command);

  /** 设置课次的课程/教师/教室；草稿与已发布可改，已确认/已关闭拒绝。 */
  LessonResourceView assignLessonResources(UUID id, AssignLessonResources command);

  /** 按日期段 + 周几 + 时段批量建课次；与已有课次重叠的日期跳过。 */
  BatchLessonResult createLessons(BatchLessons command);

  /** 平移班级在日期段内所有课次的起止时间；冲突或非草稿/已发布的课次跳过。 */
  BatchLessonOutcome shiftLessons(UUID classId, ShiftLessons command);

  /** 把某一周的课表复制到下一周，新生成草稿；目标时段被占用或超出班级起止日期时跳过。 */
  BatchLessonResult copyWeek(UUID classId, CopyWeek command);

  /** 批量确认上课：只确认已按在班名单录好考勤的已发布课次，缺考勤的跳过。 */
  BatchLessonOutcome confirmLessons(BatchLessonIds command);

  /** 删除草稿课次；已发布的课次需先撤回，不在此处放宽。 */
  void deleteLesson(UUID id, long expectedVersion);

  /** 执行链式调课方案：同一事务内按顺序移动，任一步失败整体回滚；返回被移动的课次 id。 */
  List<UUID> executeSwapScheme(UUID lessonId, ExecuteSwapScheme command);

  LessonView swapLessons(UUID id, SwapLessons command);

  LessonView createMakeupLesson(UUID sourceLessonId, CreateMakeupLesson command);

  LessonView confirmAttendance(UUID id, ConfirmAttendance command);

  /** 只落考勤、不确认课次；批量录考勤用。 */
  LessonView saveAttendance(UUID id, SaveAttendance command);

  /** 修改课次备注；DRAFT/PUBLISHED 可改，已确认/已关闭只读。 */
  LessonView updateLessonNote(UUID id, UpdateLessonNote command);

  /** 关闭课次并固化消费；需要单独权限。 */
  LessonView closeLesson(UUID id, LessonVersion command);

  /** 记一条期初已消耗（迁移用）：无课次，计入已消耗课时与金额。 */
  void openingConsumption(UUID enrollmentId, OpeningConsumption command);

  /** 教师模式：把某教师日期段内的课次整体改派给另一位教师。 */
  BatchLessonOutcome reassignTeacher(UUID teacherId, ReassignTeacher command);

  /** 标记一条站内信已读。 */
  void markNotificationRead(UUID id);
}
