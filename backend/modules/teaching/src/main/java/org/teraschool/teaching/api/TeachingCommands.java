package org.teraschool.teaching.api;

import java.util.UUID;

public interface TeachingCommands {
  ClassView createClass(CreateClass command);

  /** 结班并关闭其报名在班关系；只允许没有未结课次的班级。 */
  ClassView closeClass(UUID classId, LessonVersion command);

  /** 取消没有课次和有效报名关系的空班；保留历史记录并要求原因与版本。 */
  ClassView cancelClass(UUID classId, CancelClass command);

  ClassView addMember(UUID classId, AddClassMember command);

  LessonView createLesson(CreateLesson command);

  LessonView publishLesson(UUID id, LessonVersion command);

  LessonView confirmAttendance(UUID id, ConfirmAttendance command);

  /** 关闭课次并固化消费；需要单独权限。 */
  LessonView closeLesson(UUID id, LessonVersion command);

  /** 记一条期初已消耗（迁移用）：无课次，计入已消耗课时与金额。 */
  void openingConsumption(UUID enrollmentId, OpeningConsumption command);
}
