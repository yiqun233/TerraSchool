package org.teraschool.server;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.teraschool.teaching.api.*;

/** 教务的 HTTP 适配：班级、课次、考勤与消费固化都在应用服务内完成授权与校验。 */
@RestController
@RequestMapping(produces = "application/json")
class TeachingHttp {
  private final org.teraschool.teaching.internal.SchedulingService scheduling;
  private final TeachingQueries queries;
  private final TeachingCommands commands;

  TeachingHttp(
      TeachingQueries queries,
      TeachingCommands commands,
      org.teraschool.teaching.internal.SchedulingService scheduling) {
    this.queries = queries;
    this.commands = commands;
    this.scheduling = scheduling;
  }

  @GetMapping("/api/teaching/capabilities")
  TeachingCapabilities capabilities() {
    return queries.capabilities();
  }

  @GetMapping("/api/teaching/classes")
  List<ClassView> classes(@RequestParam UUID schoolId) {
    return queries.classes(schoolId);
  }

  @GetMapping("/api/teaching/classes/{id}/members")
  List<ClassMemberView> members(@PathVariable UUID id) {
    return queries.members(id);
  }

  @GetMapping("/api/teaching/lessons")
  List<LessonView> lessons(@RequestParam UUID classId) {
    return queries.lessons(classId);
  }

  @GetMapping("/api/teaching/lessons/{id}/attendance")
  List<AttendanceInput> attendance(@PathVariable UUID id) {
    return queries.attendance(id);
  }

  /** 单条报名的课时对账：购买、已消耗与剩余。 */
  @GetMapping("/api/teaching/enrollments/{id}/reconciliation")
  EnrollmentReconciliation reconciliation(@PathVariable UUID id) {
    return queries.reconciliation(id);
  }

  @PostMapping(value = "/api/teaching/classes", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  ClassView createClass(@RequestBody CreateClass command) {
    return commands.createClass(command);
  }

  @PostMapping(value = "/api/teaching/classes/{id}/members", consumes = "application/json")
  ClassView addMember(@PathVariable UUID id, @RequestBody AddClassMember command) {
    return commands.addMember(id, command);
  }

  @PostMapping(value = "/api/teaching/lessons", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  LessonView createLesson(@RequestBody CreateLesson command) {
    return commands.createLesson(command);
  }

  @PostMapping(value = "/api/teaching/lessons/{id}/publish", consumes = "application/json")
  LessonView publish(@PathVariable UUID id, @RequestBody LessonVersion command) {
    return commands.publishLesson(id, command);
  }

  /** 授课确认与考勤登记；必须覆盖全部在班报名。 */
  @PostMapping(value = "/api/teaching/lessons/{id}/attendance", consumes = "application/json")
  LessonView confirmAttendance(@PathVariable UUID id, @RequestBody ConfirmAttendance command) {
    return commands.confirmAttendance(id, command);
  }

  /** 关闭课次并固化消费；需要单独权限。 */
  @PostMapping(value = "/api/teaching/lessons/{id}/close", consumes = "application/json")
  LessonView close(@PathVariable UUID id, @RequestBody LessonVersion command) {
    return commands.closeLesson(id, command);
  }

  /** 自动排课预览：只计算可用与冲突，不写库。 */
  @PostMapping(value = "/api/teaching/classes/{id}/schedule/preview", consumes = "application/json")
  SchedulePreview schedulePreview(@PathVariable UUID id, @RequestBody ScheduleRule rule) {
    return scheduling.preview(id, rule);
  }

  /** 确认排课：只把可用时段写成草稿课次，发布仍走既有状态机。 */
  @PostMapping(value = "/api/teaching/classes/{id}/schedule/confirm", consumes = "application/json")
  ScheduleResult scheduleConfirm(@PathVariable UUID id, @RequestBody ScheduleRule rule) {
    return scheduling.confirm(id, rule);
  }
}
