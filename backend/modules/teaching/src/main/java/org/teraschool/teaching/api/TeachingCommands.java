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
}
