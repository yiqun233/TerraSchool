package org.teraschool.archive.api;

import java.util.Set;

/** 已按请求人字段权限脱敏的学员 CSV 内容和其中实际暴露的敏感字段。 */
public record StudentCsvExport(String content, Set<String> exposedFields) {
  public StudentCsvExport {
    exposedFields = Set.copyOf(exposedFields);
  }
}
