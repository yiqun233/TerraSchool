package org.teraschool.enrollment.api;

/** 报名稳定错误码；不暴露数据库约束与内部异常。 */
public class EnrollmentConflict extends RuntimeException {
  private final String code;

  public EnrollmentConflict(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
