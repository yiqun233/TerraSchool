package org.teraschool.platform.api;

public class AuditUnavailable extends RuntimeException {
  public AuditUnavailable() {
    super("AUDIT_UNAVAILABLE");
  }
}
