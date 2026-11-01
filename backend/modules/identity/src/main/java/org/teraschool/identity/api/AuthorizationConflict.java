package org.teraschool.identity.api;

/** 稳定业务码，不携带账号名或数据库细节。 */
public class AuthorizationConflict extends RuntimeException {
  private final String code;

  public AuthorizationConflict(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
