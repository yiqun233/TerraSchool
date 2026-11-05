package org.teraschool.server;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 报名、收款和课次的大文件导出共用可恢复任务接口。 */
@RestController
@RequestMapping(value = "/api/exports", produces = "application/json")
class ExportHttp {
  private final StudentExportTaskService tasks;

  ExportHttp(StudentExportTaskService tasks) {
    this.tasks = tasks;
  }

  @PostMapping(consumes = "application/json")
  ResponseEntity<StudentExportTask> create(@RequestBody CreateExport command) {
    return ResponseEntity.accepted().body(tasks.create(command));
  }

  @GetMapping
  List<StudentExportTask> list(@RequestParam String exportType, @RequestParam UUID schoolId) {
    return tasks.list(exportType, schoolId);
  }

  @GetMapping("/{id}")
  StudentExportTask status(@PathVariable UUID id) {
    return tasks.get(id);
  }

  @PostMapping("/{id}/cancel")
  StudentExportTask cancel(@PathVariable UUID id) {
    return tasks.cancel(id);
  }

  @PostMapping("/{id}/retry")
  StudentExportTask retry(@PathVariable UUID id) {
    return tasks.retry(id);
  }

  @GetMapping("/{id}/download")
  ResponseEntity<byte[]> download(@PathVariable UUID id) {
    var task = tasks.get(id);
    boolean xlsx = "XLSX".equals(task.format());
    var filename =
        task.exportType().toLowerCase(Locale.ROOT) + "-" + id + (xlsx ? ".xlsx" : ".csv");
    var contentType =
        xlsx
            ? MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            : MediaType.parseMediaType("text/csv; charset=UTF-8");
    return ResponseEntity.ok()
        .contentType(contentType)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .body(tasks.download(id));
  }
}
