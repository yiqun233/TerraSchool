package org.teraschool.organization.api;

/** 对调用入口公开稳定错误码，不暴露内部持久化异常。 */
public class OrganizationConflict extends RuntimeException {
  private final String code;

  public OrganizationConflict(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
