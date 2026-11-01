package org.teraschool.identity.internal;

import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import org.teraschool.identity.api.*;
import org.teraschool.platform.api.*;

/** 公开修改入口包含独立失败审计；成功记录加入原授权事务，外层回滚也补记。 */
@Service
public class AuditedAuthorizationManagement
    implements AuthorizationManagement, RoleTemplateManagement, AccountLifecycle {
  private final AccountAuthorizations target;
  private final RoleTemplates templates;
  private final AccountLifecycleService lifecycle;
  private final CurrentActor actors;
  private final FailureAudit failures;

  public AuditedAuthorizationManagement(
      AccountAuthorizations target,
      RoleTemplates templates,
      AccountLifecycleService lifecycle,
      CurrentActor actors,
      FailureAudit failures) {
    this.target = target;
    this.templates = templates;
    this.lifecycle = lifecycle;
    this.actors = actors;
    this.failures = failures;
  }

  public List<Option> accounts(String search) {
    return target.accounts(search);
  }

  public List<Option> schools(String search) {
    return target.schools(search);
  }

  public List<RoleOption> roles(String search) {
    return target.roles(search);
  }

  public AccountAuthorization get(UUID account) {
    return target.get(account);
  }

  private AuditSubject subject() {
    try {
      return actors.auditSubject().orElse(null);
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  private FailureAudit.Entry entry(
      UUID attempt,
      UUID trace,
      AuditSubject subject,
      RequestTrace.Source source,
      UUID target,
      FailureAudit.Action action,
      String code,
      FailureAudit.Result result) {
    return new FailureAudit.Entry(
        attempt,
        trace,
        subject == null ? null : subject.tenantId(),
        subject == null ? null : subject.accountId(),
        action,
        source,
        result,
        code,
        target);
  }

  @Override
  public AccountAuthorization replace(UUID account, Replacement input) {
    return invoke(
        FailureAudit.Action.AUTHORIZATION_REPLACE, account, () -> target.replace(account, input));
  }

  public List<RoleTemplateManagement.RoleSummary> listRoles(
      String search, boolean includeInactive) {
    return templates.listRoles(search, includeInactive);
  }

  public RoleTemplateManagement.RoleTemplate getRole(UUID id) {
    return templates.getRole(id);
  }

  public RoleTemplateManagement.RoleTemplate createRole(RoleTemplateManagement.CreateRole input) {
    return invoke(
        FailureAudit.Action.ROLE_TEMPLATE_CREATE, null, () -> templates.createRole(input));
  }

  public RoleTemplateManagement.RoleTemplate replaceRole(
      UUID id, RoleTemplateManagement.RoleChange input) {
    return invoke(
        FailureAudit.Action.ROLE_TEMPLATE_REPLACE, id, () -> templates.replaceRole(id, input));
  }

  public AccountLifecycle.AccountView getAccount(UUID id) {
    return lifecycle.getAccount(id);
  }

  public AccountLifecycle.AccountView createAccount(AccountLifecycle.CreateAccount input) {
    return invoke(FailureAudit.Action.ACCOUNT_CREATE, null, () -> lifecycle.createAccount(input));
  }

  public AccountLifecycle.AccountView changeStatus(
      UUID id, AccountLifecycle.ChangeAccountStatus input) {
    return invoke(FailureAudit.Action.ACCOUNT_STATUS, id, () -> lifecycle.changeStatus(id, input));
  }

  public AccountLifecycle.AccountView resetPassword(
      UUID id, AccountLifecycle.ResetAccountPassword input) {
    return invoke(
        FailureAudit.Action.ACCOUNT_PASSWORD_RESET, id, () -> lifecycle.resetPassword(id, input));
  }

  public AccountLifecycle.AccountView revokeSessions(
      UUID id, AccountLifecycle.RevokeAccountSessions input) {
    return invoke(
        FailureAudit.Action.ACCOUNT_SESSIONS_REVOKE, id, () -> lifecycle.revokeSessions(id, input));
  }

  private <T> T invoke(
      FailureAudit.Action action, UUID account, java.util.function.Supplier<T> work) {
    try (var scope = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      try {
        var result = work.get();
        if (TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isSynchronizationActive()) {
          UUID affected =
              account == null && result instanceof RoleTemplateManagement.RoleTemplate role
                  ? role.id()
                  : account == null && result instanceof AccountLifecycle.AccountView view
                      ? view.id()
                      : account;
          var who = subject();
          var trace = RequestTrace.id();
          var source = RequestTrace.source();
          var attempt = UUID.randomUUID();
          TransactionSynchronizationManager.registerSynchronization(
              new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                  if (status == STATUS_COMMITTED) return;
                  String code =
                      status == STATUS_ROLLED_BACK
                          ? "TRANSACTION_ROLLED_BACK"
                          : "TRANSACTION_OUTCOME_UNKNOWN";
                  try {
                    failures.append(
                        entry(
                            attempt,
                            trace,
                            who,
                            source,
                            affected,
                            action,
                            code,
                            FailureAudit.Result.FAILED));
                  } catch (AuditUnavailable unavailable) {
                    org.slf4j.LoggerFactory.getLogger(getClass())
                        .error(
                            "AUTHORIZATION_AUDIT_AFTER_COMPLETION_FAILED trace={} code={}",
                            trace,
                            code);
                  }
                }
              });
        }
        return result;
      } catch (RuntimeException error) {
        String code =
            error instanceof AccessDeniedException
                ? "ACCESS_DENIED"
                : error instanceof IllegalArgumentException
                    ? "INVALID_INPUT"
                    : error instanceof AuthorizationConflict conflict
                        ? conflict.code()
                        : "OPERATION_FAILED";
        failures.append(
            entry(
                UUID.randomUUID(),
                RequestTrace.id(),
                subject(),
                RequestTrace.source(),
                account,
                action,
                code,
                code.equals("OPERATION_FAILED")
                    ? FailureAudit.Result.FAILED
                    : FailureAudit.Result.REJECTED));
        throw error;
      }
    }
  }
}
