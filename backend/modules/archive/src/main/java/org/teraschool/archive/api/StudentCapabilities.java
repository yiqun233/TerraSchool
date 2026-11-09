package org.teraschool.archive.api;

import java.util.List;

/**
 * 学员档案能力快照。
 *
 * <p>{@code readableSchools} 只用于筛选选项与名称显示，不代表可修改或可导出； 前端不能用它替代服务端的数据范围校验。
 */
public record StudentCapabilities(
    boolean canRead,
    boolean canMaintain,
    boolean canReadSensitive,
    boolean canAssign,
    List<StudentSchoolOption> maintainableSchools,
    boolean canExport,
    List<StudentSchoolOption> exportableSchools,
    List<StudentSchoolOption> readableSchools) {
  public StudentCapabilities {
    maintainableSchools = List.copyOf(maintainableSchools);
    exportableSchools = List.copyOf(exportableSchools);
    readableSchools = List.copyOf(readableSchools);
  }

  public record StudentSchoolOption(java.util.UUID id, String name) {}
}
