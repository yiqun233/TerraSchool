package org.teraschool.archive.api;

import java.util.List;
import java.util.UUID;

/**
 * 当前账号可用的档案能力。
 *
 * <p>学校列表只包含“可维护学员档案”的学校，供新建表单选择；它不等于组织维护范围。
 */
public record StudentCapabilities(
    boolean canRead,
    boolean canMaintain,
    boolean canReadSensitive,
    List<StudentSchoolOption> maintainableSchools) {
  public StudentCapabilities {
    maintainableSchools = List.copyOf(maintainableSchools);
  }

  public record StudentSchoolOption(UUID id, String name) {}
}
