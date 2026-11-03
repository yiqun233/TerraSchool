package org.teraschool.admissions.api;

public class CustomerConflict extends RuntimeException {
  public CustomerConflict(String code) {
    super(code);
  }

  public String code() {
    return getMessage();
  }
}
