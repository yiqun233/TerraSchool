package org.teraschool.identity.api;

import java.util.UUID;

/** 账号与员工档案分离；凭据从不出现在响应或审计快照中。 */
public interface AccountLifecycle {
  record AccountView(UUID id, String login, boolean active, long version) {}

  record CreateAccount(String login, String password) {
    @Override
    public String toString() {
      return "CreateAccount[login=" + login + ", password=<redacted>]";
    }
  }

  record ChangeAccountStatus(Long expectedVersion, Boolean active) {}

  record ResetAccountPassword(Long expectedVersion, String password) {
    @Override
    public String toString() {
      return "ResetAccountPassword[expectedVersion=" + expectedVersion + ", password=<redacted>]";
    }
  }

  record RevokeAccountSessions(Long expectedVersion) {}

  AccountView getAccount(UUID id);

  AccountView createAccount(CreateAccount input);

  AccountView changeStatus(UUID id, ChangeAccountStatus input);

  AccountView resetPassword(UUID id, ResetAccountPassword input);

  AccountView revokeSessions(UUID id, RevokeAccountSessions input);
}
