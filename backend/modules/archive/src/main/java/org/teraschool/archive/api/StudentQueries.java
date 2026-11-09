package org.teraschool.archive.api;

import java.util.UUID;

public interface StudentQueries {
  StudentPage list(int page, int size, String search, String status, java.util.UUID schoolId);

  /** 兼容既有调用：不按状态、学校过滤。 */
  default StudentPage list(int page, int size, String search) {
    return list(page, size, search, "", null);
  }

  StudentCounts counts(String search, java.util.UUID schoolId);

  default StudentCounts counts(String search) {
    return counts(search, null);
  }

  StudentProfile detail(UUID id);

  StudentCapabilities capabilities();

  java.util.List<org.teraschool.organization.api.OrganizationQueries.EmployeeAssignmentOption>
      assignmentOptions(UUID schoolId);

  /**
   * 导出学员 CSV：字段按字段级授权脱敏，导出本身写审计。
   *
   * <p>返回的是完整 CSV 文本（含表头），调用方直接作为附件返回，不在服务端落文件。
   */
  String exportCsv(UUID schoolId);

  StudentCsvExport exportCsvArtifact(UUID schoolId);

  void authorizeCsvDownload(UUID schoolId, java.util.Set<String> exposedFields);

  java.util.List<StudentMergeHistory> mergeHistory(UUID primaryStudentId);
}
