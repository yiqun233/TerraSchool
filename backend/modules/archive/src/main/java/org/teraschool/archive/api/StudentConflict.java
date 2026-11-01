package org.teraschool.archive.api;

/** 档案动作的稳定错误码；不暴露数据库异常和敏感字段内容。 */
public class StudentConflict extends RuntimeException {
  private final String code;

  public StudentConflict(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
