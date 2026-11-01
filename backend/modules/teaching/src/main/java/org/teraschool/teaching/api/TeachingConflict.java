package org.teraschool.teaching.api;

/** 教务稳定错误码；不暴露数据库约束与内部异常。 */
public class TeachingConflict extends RuntimeException {
  private final String code;

  public TeachingConflict(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
