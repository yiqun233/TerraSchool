package org.teraschool.approval.api;

/** 审批稳定错误码。 */
public class ApprovalConflict extends RuntimeException {
  private final String code;

  public ApprovalConflict(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
