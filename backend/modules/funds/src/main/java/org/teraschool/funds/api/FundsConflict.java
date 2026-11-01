package org.teraschool.funds.api;

/** 资金稳定错误码；不暴露数据库约束与内部异常。 */
public class FundsConflict extends RuntimeException {
  private final String code;

  public FundsConflict(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
