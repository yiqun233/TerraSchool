package org.teraschool.organization.api;

import java.util.UUID;

/** 仅员工本人维护的邮件连接配置；密码只写入，不通过接口返回。 */
public interface EmployeeEmailConfiguration {
  record EmailConfigurationView(
      UUID employeeId,
      String smtpHost,
      String smtpUser,
      String senderAddress,
      boolean hasCredential,
      long version) {}

  record ChangeEmailConfiguration(
      Long expectedVersion,
      String smtpHost,
      String smtpUser,
      String senderAddress,
      String credentialAction,
      String credential) {
    @Override
    public String toString() {
      return "ChangeEmailConfiguration[expectedVersion="
          + expectedVersion
          + ", credential=<redacted>]";
    }
  }

  EmailConfigurationView getMyEmailConfiguration();

  EmailConfigurationView changeMyEmailConfiguration(ChangeEmailConfiguration input);
}
