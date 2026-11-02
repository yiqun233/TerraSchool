package org.teraschool.teaching.api;

import java.util.UUID;

public interface TeachingCommands {
  ClassView createClass(CreateClass command);

  ClassView addMember(UUID classId, AddClassMember command);

  LessonView createLesson(CreateLesson command);

  LessonView publishLesson(UUID id, LessonVersion command);

  LessonView confirmAttendance(UUID id, ConfirmAttendance command);

  /** 关闭课次并固化消费；需要单独权限。 */
  LessonView closeLesson(UUID id, LessonVersion command);

  /** 记一条期初已消耗（迁移用）：无课次，计入已消耗课时与金额。 */
  void openingConsumption(UUID enrollmentId, OpeningConsumption command);
}
