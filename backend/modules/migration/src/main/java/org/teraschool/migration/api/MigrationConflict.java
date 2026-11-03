package org.teraschool.migration.api;

/** 迁移操作因安全边界或批次不一致被拒绝。 */
public class MigrationConflict extends RuntimeException {
  public MigrationConflict(String code) {
    super(code);
  }

  public String code() {
    return getMessage();
  }
}
