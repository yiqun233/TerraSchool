package org.teraschool.admissions.internal;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.teraschool.admissions.api.*;
import org.teraschool.identity.api.AuditSubject;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.platform.api.*;

/** 完整命令的失败审计独立于业务事务；嵌套调用外层回滚也须留痕。 */
@Service
@Primary
class AuditedChannelCommands
    implements ChannelCommands,
        CustomerCommands,
        CustomerFollowupCommands,
        CustomerFollowupQueries {
  private final ChannelService target;
  private final CustomerService customers;
  private final CustomerFollowupService followups;
  private final CurrentActor actors;
  private final FailureAudit failures;

  AuditedChannelCommands(
      ChannelService target,
      CustomerService customers,
      CustomerFollowupService followups,
      CurrentActor actors,
      FailureAudit failures) {
    this.target = target;
    this.customers = customers;
    this.followups = followups;
    this.actors = actors;
    this.failures = failures;
  }

  @Override
  public Channel create(ChannelCreate command) {
    return invoke(FailureAudit.Action.CHANNEL_CREATE, null, () -> target.create(command));
  }

  @Override
  public Channel update(UUID id, ChannelUpdate command) {
    return invoke(FailureAudit.Action.CHANNEL_UPDATE, id, () -> target.update(id, command));
  }

  @Override
  public CustomerCreateResult create(CustomerCreate command) {
    return invoke(FailureAudit.Action.CUSTOMER_CREATE, null, () -> customers.create(command));
  }

  @Override
  public CustomerCreateResult cancel(CustomerCreate command) {
    return invoke(FailureAudit.Action.CUSTOMER_CANCEL, null, () -> customers.cancel(command));
  }

  @Override
  public CustomerPhone revealPhone(UUID id) {
    return invoke(FailureAudit.Action.CUSTOMER_PHONE_VIEW, id, () -> customers.revealPhone(id));
  }

  @Override
  public CustomerFollowupQueries.Followup create(UUID customerId, FollowupCreate command) {
    return invoke(
        FailureAudit.Action.CUSTOMER_FOLLOWUP_CREATE,
        customerId,
        () -> followups.create(customerId, command));
  }

  @Override
  public CustomerFollowupQueries.FollowupPage list(UUID customerId, int page, int size) {
    return invoke(
        FailureAudit.Action.CUSTOMER_FOLLOWUP_READ,
        customerId,
        () -> followups.list(customerId, page, size));
  }

  private <T> T invoke(FailureAudit.Action action, UUID resource, Supplier<T> work) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      try {
        T result = work.get();
        if (TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isSynchronizationActive()) {
          var subject = subject();
          var traceId = RequestTrace.id();
          var source = RequestTrace.source();
          var attempt = UUID.randomUUID();
          var affected =
              resource == null && result instanceof Channel channel
                  ? channel.id()
                  : resource == null
                          && result instanceof CustomerCreateResult created
                          && created.customer() != null
                      ? created.customer().id()
                      : resource;
          TransactionSynchronizationManager.registerSynchronization(
              new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                  if (status == STATUS_COMMITTED) return;
                  var code =
                      status == STATUS_ROLLED_BACK
                          ? "TRANSACTION_ROLLED_BACK"
                          : "TRANSACTION_OUTCOME_UNKNOWN";
                  try {
                    failures.append(
                        new FailureAudit.Entry(
                            attempt,
                            traceId,
                            subject == null ? null : subject.tenantId(),
                            subject == null ? null : subject.accountId(),
                            action,
                            source,
                            FailureAudit.Result.FAILED,
                            code,
                            affected));
                  } catch (AuditUnavailable unavailable) {
                    org.slf4j.LoggerFactory.getLogger(getClass())
                        .error("AUDIT_AFTER_COMPLETION_FAILED trace={} code={}", traceId, code);
                  }
                }
              });
        }
        return result;
      } catch (RuntimeException error) {
        var subject = subject();
        var code =
            error instanceof AccessDeniedException
                ? "ACCESS_DENIED"
                : error instanceof IllegalArgumentException
                    ? "INVALID_INPUT"
                    : error instanceof ChannelConflict conflict
                        ? conflict.code()
                        : error instanceof CustomerConflict conflict
                            ? conflict.code()
                            : "OPERATION_FAILED";
        failures.append(
            new FailureAudit.Entry(
                UUID.randomUUID(),
                RequestTrace.id(),
                subject == null ? null : subject.tenantId(),
                subject == null ? null : subject.accountId(),
                action,
                RequestTrace.source(),
                code.equals("OPERATION_FAILED")
                    ? FailureAudit.Result.FAILED
                    : FailureAudit.Result.REJECTED,
                code,
                resource));
        throw error;
      }
    }
  }

  private AuditSubject subject() {
    try {
      return actors.auditSubject().orElse(null);
    } catch (RuntimeException unavailable) {
      return null;
    }
  }
}
