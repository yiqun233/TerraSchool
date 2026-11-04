package org.teraschool.server;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.teraschool.teaching.api.*;

/** 教务的 HTTP 适配：班级、课次、考勤与消费固化都在应用服务内完成授权与校验。 */
@RestController
@RequestMapping(produces = "application/json")
class TeachingHttp {
  private final org.teraschool.teaching.api.SchedulingOperations scheduling;
  private final TeachingQueries queries;
  private final TeachingCommands commands;

  TeachingHttp(
      TeachingQueries queries,
      TeachingCommands commands,
      org.teraschool.teaching.api.SchedulingOperations scheduling) {
    this.queries = queries;
    this.commands = commands;
    this.scheduling = scheduling;
  }

  @GetMapping("/api/teaching/capabilities")
  TeachingCapabilities capabilities() {
    return queries.capabilities();
  }

  @GetMapping("/api/teaching/me/workbench")
  TeachingQueries.TeacherWorkbench myWorkbench(@RequestParam String from, @RequestParam String to) {
    return queries.myWorkbench(from, to);
  }

  @GetMapping("/api/teaching/me/classes/{id}")
  TeachingQueries.TeacherClassWorkbench myClass(@PathVariable UUID id) {
    return queries.myClass(id);
  }

  @GetMapping("/api/teaching/reports/resource-daily-schedule")
  TeachingQueries.DailyResourceSchedule dailyResourceSchedule(
      @RequestParam UUID schoolId,
      @RequestParam String day,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "100") int pageSize) {
    return queries.dailyResourceSchedule(schoolId, day, page, pageSize);
  }

  @GetMapping("/api/teaching/reports/student-consumption")
  TeachingQueries.StudentConsumptionPage studentConsumption(
      @RequestParam UUID schoolId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return queries.studentConsumption(schoolId, page, size);
  }

  @GetMapping("/api/teaching/reports/attendance")
  TeachingQueries.AttendanceReport attendanceReport(
      @RequestParam UUID schoolId, @RequestParam String from, @RequestParam String to) {
    return queries.attendanceReport(schoolId, from, to);
  }

  @GetMapping("/api/teaching/classes")
  List<ClassView> classes(@RequestParam UUID schoolId) {
    return queries.classes(schoolId);
  }

  @PostMapping(value = "/api/teaching/schedule/batch/preview", consumes = "application/json")
  ScheduleBatchPreview previewBatch(@RequestBody ScheduleBatchRequest request) {
    return scheduling.previewBatch(request);
  }

  @PostMapping(value = "/api/teaching/schedule/batch/confirm", consumes = "application/json")
  ScheduleBatchResult confirmBatch(@RequestBody ScheduleBatchRequest request) {
    return scheduling.confirmBatch(request);
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

  /** 期初已消耗（迁移用）：无课次，计入已消耗课时与金额。 */
  @PostMapping(
      value = "/api/teaching/enrollments/{id}/opening-consumption",
      consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  void openingConsumption(@PathVariable UUID id, @RequestBody OpeningConsumption command) {
    commands.openingConsumption(id, command);
  }

  /** 课次 CSV 导出：按学校与日期范围，导出写审计。 */
  @GetMapping(value = "/api/teaching/lessons/export", produces = "text/csv; charset=UTF-8")
  org.springframework.http.ResponseEntity<String> exportLessons(
      @RequestParam UUID schoolId,
      @RequestParam(defaultValue = "") String from,
      @RequestParam(defaultValue = "") String to) {
    return org.springframework.http.ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=\"lessons.csv\"")
        .body(queries.exportLessonsCsv(schoolId, from, to));
  }

  @PostMapping(value = "/api/teaching/classes", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  ClassView createClass(@RequestBody CreateClass command) {
    return commands.createClass(command);
  }

  @PostMapping(value = "/api/teaching/classes/{id}/close", consumes = "application/json")
  ClassView closeClass(@PathVariable UUID id, @RequestBody LessonVersion command) {
    return commands.closeClass(id, command);
  }

  @PostMapping(value = "/api/teaching/classes/{id}/cancel", consumes = "application/json")
  ClassView cancelClass(@PathVariable UUID id, @RequestBody CancelClass command) {
    return commands.cancelClass(id, command);
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

  /** 资源不可用时间（教师活动/休假、教室预订）。 */
  @PostMapping(value = "/api/teaching/resource-blocks", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  ResourceBlockView createBlock(@RequestBody ResourceBlock command) {
    return scheduling.createBlock(command);
  }

  @GetMapping("/api/teaching/resource-blocks")
  java.util.List<ResourceBlockView> blocks(
      @RequestParam UUID schoolId,
      @RequestParam(defaultValue = "") String from,
      @RequestParam(defaultValue = "") String to) {
    return scheduling.blocks(schoolId, from, to);
  }

  @DeleteMapping("/api/teaching/resource-blocks/{id}")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  void deleteBlock(@PathVariable UUID id) {
    scheduling.deleteBlock(id);
  }

  /** 学校级排课策略：最小课间间隔与是否把资源不可用降为警告。 */
  @GetMapping("/api/teaching/schedule-policy")
  SchedulePolicyView policy(@RequestParam UUID schoolId) {
    return scheduling.policyOf(schoolId);
  }

  @PostMapping(value = "/api/teaching/schedule-policy", consumes = "application/json")
  SchedulePolicyView savePolicy(@RequestParam UUID schoolId, @RequestBody SchedulePolicy command) {
    return scheduling.savePolicy(schoolId, command);
  }
}
