package org.teraschool.server;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.teraschool.archive.api.*;

/** 学员档案的 HTTP 适配：只做协议转换，授权、校验、版本与审计在应用服务内。 */
@RestController
@RequestMapping(produces = "application/json")
class StudentHttp {
  private final StudentQueries queries;
  private final StudentCommands commands;

  StudentHttp(StudentQueries queries, StudentCommands commands) {
    this.queries = queries;
    this.commands = commands;
  }

  @GetMapping("/api/archive/capabilities")
  StudentCapabilities capabilities() {
    return queries.capabilities();
  }

  @GetMapping("/api/archive/students/assignees")
  java.util.List<org.teraschool.organization.api.OrganizationQueries.EmployeeAssignmentOption>
      assignees(@RequestParam UUID schoolId) {
    return queries.assignmentOptions(schoolId);
  }

  @GetMapping("/api/archive/students")
  StudentPage list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "") String search) {
    return queries.list(page, size, search);
  }

  @GetMapping("/api/archive/students/{id}")
  StudentProfile detail(@PathVariable UUID id) {
    return queries.detail(id);
  }

  @GetMapping("/api/archive/students/{id}/merges")
  java.util.List<StudentMergeHistory> mergeHistory(@PathVariable UUID id) {
    return queries.mergeHistory(id);
  }

  @PostMapping(value = "/api/archive/students", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  StudentCreation create(@RequestBody CreateStudent command) {
    return commands.create(command);
  }

  /** 使用 POST 而非 PATCH，避免“部分字段缺失/null/清空”语义在首片被误用；整套提交带版本。 */
  @PostMapping(value = "/api/archive/students/{id}/update", consumes = "application/json")
  StudentProfile update(@PathVariable UUID id, @RequestBody UpdateStudent command) {
    return commands.update(id, command);
  }

  /** 只改启停状态；不需要提交敏感字段，避免把脱敏值写回。 */
  @PostMapping(value = "/api/archive/students/{id}/status", consumes = "application/json")
  StudentProfile changeStatus(@PathVariable UUID id, @RequestBody ChangeStudentStatus command) {
    return commands.changeStatus(id, command);
  }

  @PostMapping(value = "/api/archive/students/{id}/owner", consumes = "application/json")
  StudentProfile assignOwner(@PathVariable UUID id, @RequestBody AssignStudent command) {
    return commands.assignOwner(id, command);
  }

  @PostMapping(value = "/api/archive/students/{id}/merge", consumes = "application/json")
  StudentMergeResult merge(@PathVariable UUID id, @RequestBody MergeStudent command) {
    return commands.merge(id, command);
  }

  @PostMapping(value = "/api/archive/students/{id}/merge/reverse", consumes = "application/json")
  StudentMergeResult reverseMerge(@PathVariable UUID id, @RequestBody ReverseStudentMerge command) {
    return commands.reverseMerge(id, command);
  }

  /** 导出学员 CSV：字段按字段级授权脱敏，导出写审计。 */
  @GetMapping(value = "/api/archive/students/export", produces = "text/csv; charset=UTF-8")
  org.springframework.http.ResponseEntity<String> export(@RequestParam UUID schoolId) {
    var csv = queries.exportCsv(schoolId);
    return org.springframework.http.ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=\"students-" + schoolId + ".csv\"")
        .body(csv);
  }
}
