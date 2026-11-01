package org.teraschool.organization.internal;

import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.*;
import org.teraschool.organization.api.EmployeeAccountLinks.*;
import org.teraschool.organization.api.OrganizationConflict;
import org.teraschool.platform.api.RequestTrace;
import tools.jackson.databind.json.JsonMapper;

@Service
public class EmployeeAccountLinkService {
  private final AccountLinking linking;
  private final CurrentActor actors;
  private final DSLContext sql;
  private final SchoolWriteGuard guard;
  private final JsonMapper json = JsonMapper.builder().build();

  public EmployeeAccountLinkService(
      AccountLinking linking, CurrentActor actors, DSLContext sql, SchoolWriteGuard guard) {
    this.linking = linking;
    this.actors = actors;
    this.sql = sql;
    this.guard = guard;
  }

  public AccountLink get(UUID id) {
    if (id == null) throw new IllegalArgumentException("需要员工身份");
    return linking.withAccount(
        null,
        actor -> {
          var employee =
              sql.fetchOne(
                  "select school_id,account_id,version from organization.employee where id=?", id);
          if (employee == null
              || !actor.schools().contains(employee.get("school_id", UUID.class))
              || !actors
                  .requireResourceInTransaction("EMPLOYEE", true)
                  .schools()
                  .contains(employee.get("school_id", UUID.class)))
            throw new AccessDeniedException("员工不可管理");
          UUID account = employee.get("account_id", UUID.class);
          return linking.withAccount(
              account,
              unused ->
                  new AccountLink(
                      id,
                      employee.get("school_id", UUID.class),
                      account,
                      login(account),
                      employee.get("version", Long.class)));
        });
  }

  public AccountLink change(UUID id, ChangeAccountLink input) {
    if (id == null
        || input == null
        || input.expectedVersion() == null
        || input.expectedVersion() < 0) throw new IllegalArgumentException("需要员工身份和版本");
    try {
      return linking.withAccount(
          input.accountId(),
          actor -> {
            var employee =
                sql.fetchOne(
                    "select school_id,account_id,version from organization.employee where id=? for update",
                    id);
            if (employee == null
                || !actor.schools().contains(employee.get("school_id", UUID.class))
                || !actors
                    .requireResourceInTransaction("EMPLOYEE", true)
                    .schools()
                    .contains(employee.get("school_id", UUID.class)))
              throw new AccessDeniedException("员工不可管理");
            UUID school = employee.get("school_id", UUID.class);
            guard.requireActive(school);
            UUID old = employee.get("account_id", UUID.class);
            return linking.withAccount(
                old,
                ignored -> {
                  long version = employee.get("version", Long.class);
                  if (version != input.expectedVersion())
                    throw new OrganizationConflict("VERSION_CONFLICT");
                  if (!java.util.Objects.equals(old, input.accountId()))
                    sql.execute(
                        "update organization.employee_email_config set credential_nonce=null,credential_ciphertext=null,credential_key_version=null where employee_id=?",
                        id);
                  sql.execute(
                      "update organization.employee set account_id=?,version=version+1 where id=? and version=?",
                      input.accountId(),
                      id,
                      version);
                  sql.execute(
                      "insert into audit.resource_event(tenant_id,actor_id,school_id,resource_id,kind,action,before_json,after_json,trace_id) values (?,?,?,?,'EMPLOYEE','UPDATE',?::jsonb,?::jsonb,?)",
                      actor.tenantId(),
                      actor.accountId(),
                      school,
                      id,
                      json.writeValueAsString(
                          Map.of(
                              "accountId", old == null ? "" : old.toString(), "version", version)),
                      json.writeValueAsString(
                          Map.of(
                              "accountId",
                              input.accountId() == null ? "" : input.accountId().toString(),
                              "version",
                              version + 1)),
                      RequestTrace.id());
                  return new AccountLink(
                      id, school, input.accountId(), login(input.accountId()), version + 1);
                });
          });
    } catch (DuplicateKeyException duplicate) {
      throw new OrganizationConflict("ACCOUNT_ALREADY_LINKED");
    }
  }

  private String login(UUID account) {
    return account == null
        ? null
        : sql.fetchOne("select login from identity.account where id=?", account)
            .get("login", String.class);
  }
}
