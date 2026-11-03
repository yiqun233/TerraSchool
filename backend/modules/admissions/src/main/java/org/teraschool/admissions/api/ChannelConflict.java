package org.teraschool.admissions.api;

public class ChannelConflict extends RuntimeException {
  public ChannelConflict(String code) {
    super(code);
  }

  public String code() {
    return getMessage();
  }
}
