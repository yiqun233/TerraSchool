package org.teraschool.finance.api;

/** 期间相关稳定错误码，例如 PERIOD_LOCKED。 */
public class PeriodConflict extends RuntimeException {
  private final String code;

  public PeriodConflict(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
